package bot

import (
	"bytes"
	"context"
	"encoding/binary"
	"fmt"
	"strconv"
	"strings"
	"sync"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	"google.golang.org/protobuf/proto"
)

const (
	maxVideoBytes   = 25 << 20 // 25 MB
	maxTextLen      = 120
	maxAudioBytes   = 1 << 20 // limite do WhatsApp para o som da figurinha
	maxAudioSeconds = 10
)

var (
	jobs       = make(chan *events.Message, 16)
	workerOnce sync.Once
)

// Um worker só: processa uma mídia por vez para não estourar a memória do celular.
func startWorker() {
	workerOnce.Do(func() {
		go func() {
			for evt := range jobs {
				func() {
					defer func() {
						if r := recover(); r != nil {
							logf("Erro inesperado: %v", r)
						}
					}()
					process(evt)
				}()
			}
		}()
	})
}

func enqueue(evt *events.Message) {
	cli := getClient()
	if cli == nil || !isSelfChat(cli, evt.Info) {
		return
	}
	// Ignora mensagens antigas entregues ao reconectar.
	if evt.Info.Timestamp.Before(startedAt.Add(-30 * time.Second)) {
		return
	}
	select {
	case jobs <- evt:
	default:
		logf("Fila cheia, mensagem ignorada")
	}
}

func isSelfChat(cli *whatsmeow.Client, info types.MessageInfo) bool {
	if !info.IsFromMe || info.IsGroup || cli.Store.ID == nil {
		return false
	}
	if info.Chat.Server != types.DefaultUserServer && info.Chat.Server != types.HiddenUserServer {
		return false
	}
	u := info.Chat.User
	return u == cli.Store.ID.User || (!cli.Store.LID.IsEmpty() && u == cli.Store.LID.User)
}

// parseCommand separa "/cmd resto". Só texto começando com "/" é comando.
func parseCommand(s string) (cmd, arg string, ok bool) {
	s = strings.TrimSpace(s)
	if !strings.HasPrefix(s, "/") || len(s) < 2 {
		return "", "", false
	}
	s = s[1:]
	if i := strings.IndexAny(s, " \n\t"); i >= 0 {
		return strings.ToLower(s[:i]), strings.TrimSpace(s[i+1:]), true
	}
	return strings.ToLower(s), "", true
}

// parsePack lê "Nome | Autor" (autor opcional).
func parsePack(arg string) (pack, author string, hasAuthor bool) {
	if i := strings.Index(arg, "|"); i >= 0 {
		return strings.TrimSpace(arg[:i]), strings.TrimSpace(arg[i+1:]), true
	}
	return strings.TrimSpace(arg), "", false
}

const helpText = `🧩 *ZapFig: comandos*

📷 Mande uma *foto* → vira figurinha
🎞️ Mande um *GIF* → figurinha animada
🎬 *Vídeo* com legenda */s* → figurinha animada (até 8s)

🔊 Vídeo com legenda */sa* → figurinha animada *com o som* do vídeo
🎵 Responda um áudio com */sa* → coloca o som na última figurinha
📐 Legenda */s2* → figurinha esticada, quadrado perfeito (sem bordas)
↩️ Responda uma foto/vídeo com */s* ou */s2* → converte
↩️ Responda uma figurinha com */img* → vira imagem (ou GIF, se for animada)
✍️ */t seu texto* → figurinha de texto
🏷️ */pack Nome | Autor* → muda o pacote das próximas
🚫 Legenda */n* → não converte aquela foto
🏓 */ping* → testa se o bot está ligado`

type mediaJob struct {
	op       string // image | video
	download whatsmeow.DownloadableMessage
	size     uint64
	fit      string // "" = ajuste dos Ajustes; "stretch" = quadrado perfeito (/s2)
	sound    bool   // /sa: manda o som do vídeo junto com a figurinha
}

func imageJob(img *waE2E.ImageMessage, fit string) *mediaJob {
	return &mediaJob{op: "image", download: img, size: img.GetFileLength(), fit: fit}
}

func videoJob(v *waE2E.VideoMessage, fit string) *mediaJob {
	return &mediaJob{op: "video", download: v, size: v.GetFileLength(), fit: fit}
}

// fitFor diz qual ajuste um comando de figurinha pede ("" se não for comando de figurinha).
func fitFor(cmd string) (fit string, ok bool) {
	switch cmd {
	case "s", "f", "fig", "sticker":
		return "", true
	case "s2", "f2", "fig2", "quadrado":
		return "stretch", true
	case "sa", "som", "audio", "áudio":
		return "", true
	}
	return "", false
}

// isAnimatedWebP: chunk VP8X com a flag de animação (0x02).
func isSoundCmd(cmd string) bool {
	return cmd == "sa" || cmd == "som" || cmd == "audio" || cmd == "áudio"
}

// Última figurinha enviada: /sa respondendo um áudio coloca som nela.
var (
	lastMu      sync.Mutex
	lastSticker *waE2E.StickerMessage
)

func isAnimatedWebP(b []byte) bool {
	return len(b) > 21 && string(b[0:4]) == "RIFF" && string(b[12:16]) == "VP8X" && b[20]&0x02 != 0
}

func process(evt *events.Message) {
	cli := getClient()
	if cli == nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	msg := evt.Message
	c := getConfig()

	switch {
	case msg.GetImageMessage() != nil:
		img := msg.GetImageMessage()
		cmd, _, isCmd := parseCommand(img.GetCaption())
		if isCmd && cmd == "n" {
			return
		}
		if isCmd && isSoundCmd(cmd) {
			reply(ctx, cli, evt, "🔇 Foto não tem som. Faça a figurinha e depois responda um áudio com /sa.")
			return
		}
		if fit, ok := fitFor(cmd); isCmd && ok {
			makeAndSend(ctx, cli, evt, imageJob(img, fit))
		} else if !isCmd && c.autoImage {
			makeAndSend(ctx, cli, evt, imageJob(img, ""))
		}
		return

	case msg.GetVideoMessage() != nil:
		v := msg.GetVideoMessage()
		cmd, _, isCmd := parseCommand(v.GetCaption())
		if isCmd && cmd == "n" {
			return
		}
		if fit, ok := fitFor(cmd); isCmd && ok {
			job := videoJob(v, fit)
			job.sound = isSoundCmd(cmd)
			makeAndSend(ctx, cli, evt, job)
		} else if !isCmd && v.GetGifPlayback() && c.autoGif {
			makeAndSend(ctx, cli, evt, videoJob(v, ""))
		}
		return
	}

	text := msg.GetConversation()
	var quoted *waE2E.Message
	if ext := msg.GetExtendedTextMessage(); ext != nil {
		text = ext.GetText()
		quoted = ext.GetContextInfo().GetQuotedMessage()
	}
	cmd, arg, ok := parseCommand(text)
	if !ok {
		return
	}

	switch cmd {
	case "ping":
		reply(ctx, cli, evt, "🏓 pong! ZapFig v"+Version+" ligado.")
	case "ajuda", "help", "menu", "comandos":
		reply(ctx, cli, evt, helpText)
	case "t", "texto":
		if arg == "" {
			reply(ctx, cli, evt, "✍️ Use assim: /t seu texto aqui")
			return
		}
		if len([]rune(arg)) > maxTextLen {
			reply(ctx, cli, evt, fmt.Sprintf("✍️ Texto muito longo (máx. %d letras)", maxTextLen))
			return
		}
		webp, err := cb.Convert("text", nil, arg)
		if err != nil {
			fail(ctx, cli, evt, "gerar figurinha de texto", err)
			return
		}
		sendSticker(ctx, cli, evt, webp, false, nil)
	case "testesom":
		// Diagnóstico: manda o som convertido como mensagem de voz.
		var media whatsmeow.DownloadableMessage
		switch {
		case quoted.GetVideoMessage() != nil:
			media = quoted.GetVideoMessage()
		case quoted.GetAudioMessage() != nil:
			media = quoted.GetAudioMessage()
		default:
			reply(ctx, cli, evt, "🧪 Responda um vídeo ou áudio com /testesom")
			return
		}
		raw, err := cli.Download(ctx, media)
		if err != nil {
			fail(ctx, cli, evt, "baixar a mídia", err)
			return
		}
		a := uploadStickerAudio(ctx, cli, evt, raw, maxAudioSeconds)
		if a == nil {
			return
		}
		a.PTT = proto.Bool(true)
		a.ContextInfo = quoteOf(evt)
		if _, err := cli.SendMessage(ctx, evt.Info.Chat, &waE2E.Message{AudioMessage: a}); err != nil {
			logf("Erro ao enviar teste de som: %v", err)
			return
		}
		logf("Teste de som enviado (%ds, %s)", a.GetSeconds(), a.GetMimetype())
	case "sa", "som", "audio", "áudio":
		switch {
		case quoted.GetVideoMessage() != nil:
			job := videoJob(quoted.GetVideoMessage(), "")
			job.sound = true
			makeAndSend(ctx, cli, evt, job)
		case quoted.GetAudioMessage() != nil:
			lastMu.Lock()
			st := lastSticker
			lastMu.Unlock()
			if st == nil {
				reply(ctx, cli, evt, "🎵 Faça uma figurinha primeiro e depois responda um áudio com /sa.")
				return
			}
			raw, err := cli.Download(ctx, quoted.GetAudioMessage())
			if err != nil {
				fail(ctx, cli, evt, "baixar o áudio", err)
				return
			}
			if a := uploadStickerAudio(ctx, cli, evt, raw, maxAudioSeconds); a != nil {
				sendStickerWithAudio(ctx, cli, evt, st, a)
			}
		default:
			reply(ctx, cli, evt, "🎵 Responda um *vídeo* com /sa (usa o som dele) ou um *áudio* com /sa (coloca o som na última figurinha).")
		}
	case "s", "f", "fig", "sticker", "s2", "f2", "fig2", "quadrado":
		fit, _ := fitFor(cmd)
		switch {
		case quoted.GetImageMessage() != nil:
			makeAndSend(ctx, cli, evt, imageJob(quoted.GetImageMessage(), fit))
		case quoted.GetVideoMessage() != nil:
			makeAndSend(ctx, cli, evt, videoJob(quoted.GetVideoMessage(), fit))
		case baseSticker(quoted) != nil:
			reply(ctx, cli, evt, "Isso já é uma figurinha 😄 Use /img para virar imagem ou GIF.")
		default:
			reply(ctx, cli, evt, "↩️ Responda uma foto ou vídeo com /"+cmd)
		}
	case "img", "imagem", "toimg":
		st := baseSticker(quoted)
		if st == nil {
			reply(ctx, cli, evt, "↩️ Responda uma figurinha com /img")
			return
		}
		data, err := cli.Download(ctx, st)
		if err != nil {
			fail(ctx, cli, evt, "baixar figurinha", err)
			return
		}
		if isAnimatedWebP(data) {
			mp4, err := cb.Convert("togif", data, "")
			if err != nil {
				fail(ctx, cli, evt, "transformar em GIF", err)
				return
			}
			sendGif(ctx, cli, evt, mp4)
			return
		}
		png, err := cb.Convert("toimage", data, "")
		if err != nil {
			fail(ctx, cli, evt, "converter figurinha", err)
			return
		}
		sendImage(ctx, cli, evt, png)
	case "pack", "pacote":
		pack, author, hasAuthor := parsePack(arg)
		if pack == "" {
			reply(ctx, cli, evt, fmt.Sprintf("🏷️ Pacote atual: *%s*\nAutor: *%s*\n\nPara mudar: /pack Nome | Autor", orDash(c.pack), orDash(c.author)))
			return
		}
		if !hasAuthor {
			author = c.author
		}
		Configure(pack, author, c.autoImage, c.autoGif, c.maxSeconds)
		if cb != nil {
			cb.OnConfigChanged(pack, author)
		}
		reply(ctx, cli, evt, fmt.Sprintf("🏷️ Pronto! Próximas figurinhas: *%s* • *%s*", pack, orDash(author)))
	default:
		reply(ctx, cli, evt, "🤔 Não conheço esse comando. Mande /ajuda")
	}
}

func orDash(s string) string {
	if s == "" {
		return "—"
	}
	return s
}

func makeAndSend(ctx context.Context, cli *whatsmeow.Client, evt *events.Message, job *mediaJob) {
	if job.op == "video" && job.size > maxVideoBytes {
		reply(ctx, cli, evt, "🎬 Vídeo muito grande (máx. 25 MB). Corte um trecho e mande de novo.")
		return
	}
	data, err := cli.Download(ctx, job.download)
	if err != nil {
		fail(ctx, cli, evt, "baixar mídia", err)
		return
	}
	logf("Convertendo %s (%d KB)…", map[string]string{"image": "foto", "video": "vídeo"}[job.op], len(data)/1024)
	t0 := time.Now()
	webp, err := cb.Convert(job.op, data, job.fit)
	if err != nil {
		fail(ctx, cli, evt, "converter", err)
		return
	}
	logf("Convertido em %.1fs", time.Since(t0).Seconds())
	var audio *waE2E.AudioMessage
	if job.sound {
		audio = uploadStickerAudio(ctx, cli, evt, data, getConfig().maxSeconds)
		if audio == nil {
			return
		}
	}
	sendSticker(ctx, cli, evt, webp, job.op == "video", audio)
}

// uploadStickerAudio converte o som de qualquer mídia para OGG Opus (formato
// das figurinhas com som) e sobe. nil se falhar (já avisa o usuário).
func uploadStickerAudio(ctx context.Context, cli *whatsmeow.Client, evt *events.Message, media []byte, seconds int) *waE2E.AudioMessage {
	ogg, err := cb.Convert("audio", media, strconv.Itoa(seconds))
	if err != nil {
		fail(ctx, cli, evt, "preparar o som", err)
		return nil
	}
	if len(ogg) == 0 {
		reply(ctx, cli, evt, "🔇 Essa mídia não tem som.")
		return nil
	}
	if len(ogg) > maxAudioBytes {
		reply(ctx, cli, evt, "🔊 O som ficou grande demais (máx. 1 MB). Use um trecho menor.")
		return nil
	}
	up, err := cli.Upload(ctx, ogg, whatsmeow.MediaAudio)
	if err != nil {
		fail(ctx, cli, evt, "enviar o som", err)
		return nil
	}
	return &waE2E.AudioMessage{
		URL:               proto.String(up.URL),
		DirectPath:        proto.String(up.DirectPath),
		MediaKey:          up.MediaKey,
		FileEncSHA256:     up.FileEncSHA256,
		FileSHA256:        up.FileSHA256,
		FileLength:        proto.Uint64(up.FileLength),
		Mimetype:          proto.String("audio/ogg; codecs=opus"),
		Seconds:           proto.Uint32(oggOpusSeconds(ogg)),
		MediaKeyTimestamp: proto.Int64(time.Now().Unix()),
	}
}

// oggOpusSeconds lê a duração pela granule position da última página OGG (48 kHz).
func oggOpusSeconds(ogg []byte) uint32 {
	i := bytes.LastIndex(ogg, []byte("OggS"))
	if i < 0 || i+14 > len(ogg) {
		return 1
	}
	g := binary.LittleEndian.Uint64(ogg[i+6 : i+14])
	sec := uint32((g + 47999) / 48000)
	if sec == 0 {
		sec = 1
	}
	return sec
}

func quoteOf(evt *events.Message) *waE2E.ContextInfo {
	return &waE2E.ContextInfo{
		StanzaID:      proto.String(evt.Info.ID),
		Participant:   proto.String(evt.Info.Sender.ToNonAD().String()),
		QuotedMessage: evt.Message,
	}
}

// baseSticker acha o StickerMessage mesmo dentro dos envelopes (com som / lottie).
func baseSticker(m *waE2E.Message) *waE2E.StickerMessage {
	if st := m.GetStickerMessage(); st != nil {
		return st
	}
	if st := m.GetAudioStickerMessage().GetMessage().GetStickerMessage(); st != nil {
		return st
	}
	return m.GetLottieStickerMessage().GetMessage().GetStickerMessage()
}

func sendSticker(ctx context.Context, cli *whatsmeow.Client, evt *events.Message, webp []byte, animated bool, audio *waE2E.AudioMessage) {
	c := getConfig()
	if withMeta, err := addStickerExif(webp, c.pack, c.author); err == nil {
		webp = withMeta
	} else {
		logf("Aviso: não consegui gravar pacote/autor: %v", err)
	}
	up, err := cli.Upload(ctx, webp, whatsmeow.MediaImage)
	if err != nil {
		fail(ctx, cli, evt, "enviar figurinha", err)
		return
	}
	st := &waE2E.StickerMessage{
		URL:               proto.String(up.URL),
		DirectPath:        proto.String(up.DirectPath),
		MediaKey:          up.MediaKey,
		FileEncSHA256:     up.FileEncSHA256,
		FileSHA256:        up.FileSHA256,
		FileLength:        proto.Uint64(up.FileLength),
		Mimetype:          proto.String("image/webp"),
		Width:             proto.Uint32(512),
		Height:            proto.Uint32(512),
		IsAnimated:        proto.Bool(animated),
		MediaKeyTimestamp: proto.Int64(time.Now().Unix()),
	}
	lastMu.Lock()
	lastSticker = proto.Clone(st).(*waE2E.StickerMessage)
	lastMu.Unlock()
	if audio != nil {
		sendStickerWithAudio(ctx, cli, evt, st, audio)
		return
	}
	st.ContextInfo = quoteOf(evt)
	if _, err = cli.SendMessage(ctx, evt.Info.Chat, &waE2E.Message{StickerMessage: st}); err != nil {
		logf("Erro ao enviar figurinha: %v", err)
		return
	}
	logf("Figurinha enviada ✅ (%d KB%s)", len(webp)/1024, map[bool]string{true: ", animada", false: ""}[animated])
	if cb != nil {
		cb.OnStickerSent(animated)
	}
}

// sendStickerWithAudio manda a figurinha com um áudio embutido (figurinha com som).
func sendStickerWithAudio(ctx context.Context, cli *whatsmeow.Client, evt *events.Message, base *waE2E.StickerMessage, audio *waE2E.AudioMessage) {
	st := proto.Clone(base).(*waE2E.StickerMessage)
	st.Audio = &waE2E.StickerMessage_AudioMessage{AudioMessage: audio}
	st.ContextInfo = quoteOf(evt)
	st.StickerSentTS = proto.Int64(time.Now().UnixMilli())
	// StickerMessage comum com o áudio no campo 26 (igual aos bots que já funcionam).
	msg := &waE2E.Message{StickerMessage: st}
	if _, err := cli.SendMessage(ctx, evt.Info.Chat, msg); err != nil {
		logf("Erro ao enviar figurinha com som: %v", err)
		reply(ctx, cli, evt, "❌ Não consegui enviar a figurinha com som.")
		return
	}
	logf("Figurinha com som enviada 🔊 (%ds de áudio)", audio.GetSeconds())
	if cb != nil {
		cb.OnStickerSent(st.GetIsAnimated())
	}
}

func sendImage(ctx context.Context, cli *whatsmeow.Client, evt *events.Message, png []byte) {
	up, err := cli.Upload(ctx, png, whatsmeow.MediaImage)
	if err != nil {
		fail(ctx, cli, evt, "enviar imagem", err)
		return
	}
	_, err = cli.SendMessage(ctx, evt.Info.Chat, &waE2E.Message{
		ImageMessage: &waE2E.ImageMessage{
			URL:           proto.String(up.URL),
			DirectPath:    proto.String(up.DirectPath),
			MediaKey:      up.MediaKey,
			FileEncSHA256: up.FileEncSHA256,
			FileSHA256:    up.FileSHA256,
			FileLength:    proto.Uint64(up.FileLength),
			Mimetype:      proto.String("image/png"),
			ContextInfo:   quoteOf(evt),
		},
	})
	if err != nil {
		logf("Erro ao enviar imagem: %v", err)
	}
}

func sendGif(ctx context.Context, cli *whatsmeow.Client, evt *events.Message, mp4 []byte) {
	up, err := cli.Upload(ctx, mp4, whatsmeow.MediaVideo)
	if err != nil {
		fail(ctx, cli, evt, "enviar GIF", err)
		return
	}
	_, err = cli.SendMessage(ctx, evt.Info.Chat, &waE2E.Message{
		VideoMessage: &waE2E.VideoMessage{
			URL:           proto.String(up.URL),
			DirectPath:    proto.String(up.DirectPath),
			MediaKey:      up.MediaKey,
			FileEncSHA256: up.FileEncSHA256,
			FileSHA256:    up.FileSHA256,
			FileLength:    proto.Uint64(up.FileLength),
			Mimetype:      proto.String("video/mp4"),
			GifPlayback:   proto.Bool(true),
			ContextInfo:   quoteOf(evt),
		},
	})
	if err != nil {
		logf("Erro ao enviar GIF: %v", err)
		return
	}
	logf("GIF enviado ✅ (%d KB)", len(mp4)/1024)
}

func reply(ctx context.Context, cli *whatsmeow.Client, evt *events.Message, text string) {
	_, err := cli.SendMessage(ctx, evt.Info.Chat, &waE2E.Message{
		ExtendedTextMessage: &waE2E.ExtendedTextMessage{
			Text:        proto.String(text),
			ContextInfo: quoteOf(evt),
		},
	})
	if err != nil {
		logf("Erro ao enviar texto: %v", err)
	}
}

func fail(ctx context.Context, cli *whatsmeow.Client, evt *events.Message, what string, err error) {
	logf("Erro ao %s: %v", what, err)
	reply(ctx, cli, evt, "❌ Não consegui "+what+". Tente de novo.")
}
