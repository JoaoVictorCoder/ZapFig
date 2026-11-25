package bot

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types/events"
	"google.golang.org/protobuf/proto"
)

const (
	maxInlineVideo = 100 << 20 // acima disso vai como documento
	maxDocument    = 500 << 20
)

var dlSlot = make(chan struct{}, 1)

var urlRe = regexp.MustCompile(`https?://[^\s<>"']+`)

// findURL pega o primeiro link do texto.
func findURL(text string) string {
	u := urlRe.FindString(text)
	return strings.TrimRight(u, ".,;:!?)]}*_~")
}

// quotedText devolve o texto de uma mensagem respondida (texto, legenda...).
func quotedText(m *waE2E.Message) string {
	switch {
	case m == nil:
		return ""
	case m.Conversation != nil:
		return m.GetConversation()
	case m.ExtendedTextMessage != nil:
		return m.GetExtendedTextMessage().GetText()
	case m.ImageMessage != nil:
		return m.GetImageMessage().GetCaption()
	case m.VideoMessage != nil:
		return m.GetVideoMessage().GetCaption()
	}
	return ""
}

// fetched é o que o lado Android devolve (JSON) depois de baixar.
type fetched struct {
	Path     string `json:"path"`
	Mime     string `json:"mime"`
	Title    string `json:"title"`
	Duration int    `json:"duration"`
	Width    int    `json:"width"`
	Height   int    `json:"height"`
	Thumb    string `json:"thumb"`
}

func isDownloadCmd(cmd string) (audio bool, ok bool) {
	switch cmd {
	case "baixar", "b", "dl", "video", "vídeo":
		return false, true
	case "mp3", "musica", "música":
		return true, true
	}
	return false, false
}

func handleDownload(ctx context.Context, cli *whatsmeow.Client, evt *events.Message, cmd, arg string, quoted *waE2E.Message) {
	audioOnly, _ := isDownloadCmd(cmd)
	link := findURL(arg)
	if link == "" {
		link = findURL(quotedText(quoted))
	}
	if link == "" {
		reply(ctx, cli, evt, "🔗 Use assim: /"+cmd+" https://link-do-video\nOu responda uma mensagem que tenha o link.")
		return
	}
	what := "vídeo"
	if audioOnly {
		what = "áudio"
	}
	reply(ctx, cli, evt, "⏳ Baixando o "+what+"…")
	logf("Baixando %s: %s", what, link)

	// Download pode demorar bem mais que uma conversão.
	dctx, cancel := context.WithTimeout(context.Background(), 15*time.Minute)
	defer cancel()

	raw, err := cb.FetchMedia(link, audioOnly)
	if err != nil {
		logf("Erro no download: %v", err)
		reply(ctx, cli, evt, "❌ Não consegui baixar: "+shortErr(err))
		return
	}
	var f fetched
	if err := json.Unmarshal([]byte(raw), &f); err != nil || f.Path == "" {
		fail(ctx, cli, evt, "ler o resultado do download", fmt.Errorf("%v (%s)", err, raw))
		return
	}
	defer os.RemoveAll(filepath.Dir(f.Path))

	st, err := os.Stat(f.Path)
	if err != nil {
		fail(ctx, cli, evt, "abrir o arquivo baixado", err)
		return
	}
	size := st.Size()
	logf("Baixado: %s (%.1f MB)", f.Title, float64(size)/(1<<20))
	if size > maxDocument {
		reply(ctx, cli, evt, fmt.Sprintf("📦 O arquivo ficou com %.0f MB, acima do limite de 500 MB.", float64(size)/(1<<20)))
		return
	}

	var thumb []byte
	if f.Thumb != "" {
		thumb, _ = os.ReadFile(f.Thumb)
	}

	switch {
	case audioOnly:
		up, err := uploadFile(dctx, cli, f.Path, whatsmeow.MediaAudio)
		if err != nil {
			fail(ctx, cli, evt, "enviar o áudio", err)
			return
		}
		_, err = cli.SendMessage(dctx, evt.Info.Chat, &waE2E.Message{AudioMessage: &waE2E.AudioMessage{
			URL:               proto.String(up.URL),
			DirectPath:        proto.String(up.DirectPath),
			MediaKey:          up.MediaKey,
			FileEncSHA256:     up.FileEncSHA256,
			FileSHA256:        up.FileSHA256,
			FileLength:        proto.Uint64(up.FileLength),
			Mimetype:          proto.String(orDefault(f.Mime, "audio/mpeg")),
			Seconds:           proto.Uint32(uint32(f.Duration)),
			MediaKeyTimestamp: proto.Int64(time.Now().Unix()),
			ContextInfo:       quoteOf(evt),
		}})
		if err == nil && f.Title != "" {
			reply(ctx, cli, evt, "🎵 "+f.Title)
		}
		finishSend(ctx, cli, evt, err, "áudio")

	case size <= maxInlineVideo:
		up, err := uploadFile(dctx, cli, f.Path, whatsmeow.MediaVideo)
		if err != nil {
			fail(ctx, cli, evt, "enviar o vídeo", err)
			return
		}
		_, err = cli.SendMessage(dctx, evt.Info.Chat, &waE2E.Message{VideoMessage: &waE2E.VideoMessage{
			URL:               proto.String(up.URL),
			DirectPath:        proto.String(up.DirectPath),
			MediaKey:          up.MediaKey,
			FileEncSHA256:     up.FileEncSHA256,
			FileSHA256:        up.FileSHA256,
			FileLength:        proto.Uint64(up.FileLength),
			Mimetype:          proto.String(orDefault(f.Mime, "video/mp4")),
			Seconds:           proto.Uint32(uint32(f.Duration)),
			Width:             proto.Uint32(uint32(f.Width)),
			Height:            proto.Uint32(uint32(f.Height)),
			Caption:           proto.String(f.Title),
			JPEGThumbnail:     thumb,
			MediaKeyTimestamp: proto.Int64(time.Now().Unix()),
			ContextInfo:       quoteOf(evt),
		}})
		finishSend(ctx, cli, evt, err, "vídeo")

	default: // grande demais para vídeo: manda como documento
		up, err := uploadFile(dctx, cli, f.Path, whatsmeow.MediaDocument)
		if err != nil {
			fail(ctx, cli, evt, "enviar o arquivo", err)
			return
		}
		name := safeFileName(f.Title) + filepath.Ext(f.Path)
		_, err = cli.SendMessage(dctx, evt.Info.Chat, &waE2E.Message{DocumentMessage: &waE2E.DocumentMessage{
			URL:               proto.String(up.URL),
			DirectPath:        proto.String(up.DirectPath),
			MediaKey:          up.MediaKey,
			FileEncSHA256:     up.FileEncSHA256,
			FileSHA256:        up.FileSHA256,
			FileLength:        proto.Uint64(up.FileLength),
			Mimetype:          proto.String(orDefault(f.Mime, "video/mp4")),
			FileName:          proto.String(name),
			Title:             proto.String(f.Title),
			JPEGThumbnail:     thumb,
			MediaKeyTimestamp: proto.Int64(time.Now().Unix()),
			ContextInfo:       quoteOf(evt),
		}})
		finishSend(ctx, cli, evt, err, "arquivo (grande demais para vídeo, foi como documento)")
	}
}

// uploadFile sobe direto do disco, sem carregar o vídeo inteiro na memória.
func uploadFile(ctx context.Context, cli *whatsmeow.Client, path string, kind whatsmeow.MediaType) (whatsmeow.UploadResponse, error) {
	in, err := os.Open(path)
	if err != nil {
		return whatsmeow.UploadResponse{}, err
	}
	defer in.Close()
	tmp, err := os.CreateTemp(filepath.Dir(path), "enc-*")
	if err != nil {
		return whatsmeow.UploadResponse{}, err
	}
	defer func() { tmp.Close(); os.Remove(tmp.Name()) }()
	return cli.UploadReader(ctx, in, tmp, kind)
}

func finishSend(ctx context.Context, cli *whatsmeow.Client, evt *events.Message, err error, what string) {
	if err != nil {
		logf("Erro ao enviar %s: %v", what, err)
		reply(ctx, cli, evt, "❌ Baixei, mas não consegui enviar o "+what+".")
		return
	}
	logf("Enviado ✅ (%s)", what)
}

func orDefault(s, def string) string {
	if s == "" {
		return def
	}
	return s
}

var badName = regexp.MustCompile(`[\\/:*?"<>|\x00-\x1f]+`)

func safeFileName(s string) string {
	s = strings.TrimSpace(badName.ReplaceAllString(s, " "))
	if s == "" {
		s = "video"
	}
	if r := []rune(s); len(r) > 80 {
		s = string(r[:80])
	}
	return s
}

// shortErr pega a linha mais útil do erro do yt-dlp.
func shortErr(err error) string {
	msg := err.Error()
	for _, line := range strings.Split(msg, "\n") {
		if strings.Contains(line, "ERROR:") {
			msg = strings.TrimSpace(line[strings.Index(line, "ERROR:")+6:])
			break
		}
	}
	if r := []rune(msg); len(r) > 200 {
		msg = string(r[:200]) + "…"
	}
	return msg
}
