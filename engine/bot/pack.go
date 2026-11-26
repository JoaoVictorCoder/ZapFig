package bot

import (
	"context"
	"fmt"
	"strconv"
	"strings"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types/events"
	"google.golang.org/protobuf/proto"
)

const (
	maxPackStickers  = 30
	maxPackVideoSize = 64 << 20 // 64 MB
	minSegmentMs     = 1000     // sobra menor que isso no fim é descartada
)

// packOptions vem de "/sapack 5 quadrado Nome do pack".
type packOptions struct {
	seconds int    // duração de cada figurinha
	fit     string // "" ou "stretch"
	name    string // nome do pacote (opcional)
}

func parsePackArgs(arg string, defSeconds int) packOptions {
	o := packOptions{seconds: defSeconds}
	var rest []string
	for _, w := range strings.Fields(arg) {
		lw := strings.ToLower(strings.TrimSuffix(w, "s"))
		if n, err := strconv.Atoi(lw); err == nil && len(rest) == 0 {
			o.seconds = n
			continue
		}
		if (lw == "quadrado" || lw == "esticar" || lw == "s2") && len(rest) == 0 {
			o.fit = "stretch"
			continue
		}
		rest = append(rest, w)
	}
	if o.seconds < 1 {
		o.seconds = 1
	}
	if o.seconds > 8 {
		o.seconds = 8
	}
	o.name = strings.TrimSpace(strings.Join(rest, " "))
	return o
}

// segmentPlan divide a duração em trechos de segMs (o último pode ser menor).
func segmentPlan(durMs, segMs int64, max int) (starts []int64, truncated bool) {
	for s := int64(0); s < durMs; s += segMs {
		if durMs-s < minSegmentMs && len(starts) > 0 {
			break
		}
		if len(starts) == max {
			return starts, true
		}
		starts = append(starts, s)
	}
	return starts, false
}

func isPackCmd(cmd string) (sound bool, ok bool) {
	switch cmd {
	case "sapack", "packsom", "fatiar":
		return true, true
	case "spack", "packmudo":
		return false, true
	}
	return false, false
}

// handleStickerPack corta um vídeo longo em várias figurinhas animadas (com o som de cada trecho).
func handleStickerPack(ctx context.Context, cli *whatsmeow.Client, evt *events.Message, video *waE2E.VideoMessage, cmd, arg string) {
	sound, _ := isPackCmd(cmd)
	c := getConfig()
	o := parsePackArgs(arg, c.maxSeconds)
	if video.GetFileLength() > maxPackVideoSize {
		reply(ctx, cli, evt, "🎬 Vídeo muito grande para pack (máx. 64 MB).")
		return
	}
	data, err := cli.Download(ctx, video)
	if err != nil {
		fail(ctx, cli, evt, "baixar o vídeo", err)
		return
	}
	idb, err := cb.Convert("prepare", data, "")
	if err != nil {
		fail(ctx, cli, evt, "preparar o vídeo", err)
		return
	}
	data = nil // libera a memória: o vídeo agora está no disco do Android
	id := string(idb)
	defer cb.Convert("release", nil, id)

	db, err := cb.Convert("duration", nil, id)
	durMs, _ := strconv.ParseInt(string(db), 10, 64)
	if err != nil || durMs <= 0 {
		fail(ctx, cli, evt, "ler a duração do vídeo", err)
		return
	}
	segMs := int64(o.seconds) * 1000
	starts, truncated := segmentPlan(durMs, segMs, maxPackStickers)

	pack := c.pack
	if o.name != "" {
		pack = o.name
	}
	packID := newPackID()
	msg := fmt.Sprintf("✂️ Vídeo de %s → *%d figurinhas* de %ds%s. Vão chegando…",
		fmtDur(durMs), len(starts), o.seconds, map[bool]string{true: " com som 🔊", false: ""}[sound])
	if truncated {
		msg += fmt.Sprintf("\n(o limite é %d por vez: vou até %s)", maxPackStickers, fmtDur(int64(len(starts))*segMs))
	}
	reply(ctx, cli, evt, msg)
	logf("Pack: %d trechos de %ds (vídeo de %s)", len(starts), o.seconds, fmtDur(durMs))

	sent, silent := 0, false
	t0 := time.Now()
	for i, start := range starts {
		seg := fmt.Sprintf("id=%s;start=%d;len=%d;fit=%s", id, start, segMs, o.fit)
		webp, err := cb.Convert("segment", nil, seg)
		if err != nil {
			logf("Pack: trecho %d falhou: %v", i+1, err)
			continue
		}
		var audio *waE2E.AudioMessage
		if sound && !silent {
			ogg, err := cb.Convert("segaudio", nil, seg)
			switch {
			case err != nil:
				logf("Pack: som do trecho %d falhou: %v", i+1, err)
			case len(ogg) == 0:
				silent = true // vídeo sem trilha de áudio: segue sem som
				reply(ctx, cli, evt, "🔇 Esse vídeo não tem som; mando as figurinhas sem áudio.")
			case len(ogg) <= maxAudioBytes:
				audio = uploadAudioOgg(ctx, cli, ogg)
			}
		}
		if err := sendPackSticker(ctx, cli, evt, webp, audio, pack, c.author, packID); err != nil {
			logf("Pack: envio %d falhou: %v", i+1, err)
			continue
		}
		sent++
		time.Sleep(600 * time.Millisecond) // não disparar tudo de uma vez
	}
	reply(ctx, cli, evt, fmt.Sprintf("✅ Pack pronto: %d de %d figurinhas em %.0fs. Pacote: *%s*",
		sent, len(starts), time.Since(t0).Seconds(), pack))
}

func uploadAudioOgg(ctx context.Context, cli *whatsmeow.Client, ogg []byte) *waE2E.AudioMessage {
	up, err := cli.Upload(ctx, ogg, whatsmeow.MediaAudio)
	if err != nil {
		logf("Pack: upload do som falhou: %v", err)
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

// sendPackSticker manda uma figurinha do pack (sem citar a mensagem, para não poluir).
func sendPackSticker(ctx context.Context, cli *whatsmeow.Client, evt *events.Message, webp []byte, audio *waE2E.AudioMessage, pack, author, packID string) error {
	if withMeta, err := addStickerExifID(webp, pack, author, packID); err == nil {
		webp = withMeta
	}
	up, err := cli.Upload(ctx, webp, whatsmeow.MediaImage)
	if err != nil {
		return err
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
		IsAnimated:        proto.Bool(true),
		MediaKeyTimestamp: proto.Int64(time.Now().Unix()),
		StickerSentTS:     proto.Int64(time.Now().UnixMilli()),
	}
	if audio != nil {
		st.Audio = &waE2E.StickerMessage_AudioMessage{AudioMessage: audio}
	}
	if _, err := cli.SendMessage(ctx, evt.Info.Chat, &waE2E.Message{StickerMessage: st}); err != nil {
		return err
	}
	if cb != nil {
		cb.OnStickerSent(true)
	}
	return nil
}

func fmtDur(ms int64) string {
	s := ms / 1000
	return fmt.Sprintf("%d:%02d", s/60, s%60)
}

// startPack roda o pack fora da fila das figurinhas (é demorado), um por vez.
func startPack(cli *whatsmeow.Client, evt *events.Message, v *waE2E.VideoMessage, cmd, arg string) {
	go func() {
		select {
		case dlSlot <- struct{}{}:
		default:
			reply(context.Background(), cli, evt, "⏳ Já tem uma tarefa longa rodando, esse pack entra na fila…")
			dlSlot <- struct{}{}
		}
		defer func() { <-dlSlot }()
		defer func() {
			if r := recover(); r != nil {
				logf("Erro inesperado no pack: %v", r)
			}
		}()
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Minute)
		defer cancel()
		handleStickerPack(ctx, cli, evt, v, cmd, arg)
	}()
}

// captionArg devolve o que vem depois do comando na legenda ("/sapack 4 Nome" -> "4 Nome").
func captionArg(caption string) string {
	_, arg, _ := parseCommand(caption)
	return arg
}
