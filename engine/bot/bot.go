// Package bot é o motor do ZapFig: um cliente WhatsApp (whatsmeow) que roda
// dentro do próprio celular e só responde no chat "você mesmo".
package bot

import (
	"context"
	"fmt"
	"path/filepath"
	"strings"
	"sync"
	"time"

	_ "github.com/mattn/go-sqlite3"
	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"go.mau.fi/whatsmeow/types/events"
	waLog "go.mau.fi/whatsmeow/util/log"
)

// Version do motor, mostrada no app.
const Version = "1.0.0"

// Callback é implementado no lado Kotlin.
type Callback interface {
	OnLog(msg string)
	OnStatus(status string)
	OnPairCode(code string)
	// OnStickerSent é chamado a cada figurinha enviada (para estatísticas).
	OnStickerSent(animated bool)
	// OnConfigChanged avisa que o usuário mudou pacote/autor via comando no chat.
	OnConfigChanged(pack string, author string)
	// FetchMedia baixa um vídeo (ou só o áudio, em MP3) de um link com yt-dlp e
	// devolve um JSON: {"path","mime","title","duration","width","height","thumb"}.
	FetchMedia(url string, audioOnly bool) (string, error)
	// Convert faz o trabalho pesado de mídia no lado Android.
	//   op "image"   -> data = jpeg/png, devolve WebP estático 512x512
	//   op "video"   -> data = mp4,      devolve WebP animado 512x512
	//   op "text"    -> arg  = texto,    devolve WebP estático
	//   op "toimage" -> data = webp,     devolve PNG
	//   op "togif"   -> data = webp animado, devolve MP4 (GIF do WhatsApp)
	//   op "audio"   -> data = vídeo/áudio, arg = segundos; devolve OGG Opus mono 48 kHz; vazio se não tiver som
	Convert(op string, data []byte, arg string) ([]byte, error)
}

type config struct {
	pack, author       string
	autoImage, autoGif bool
	maxSeconds         int
}

var (
	mu        sync.Mutex
	client    *whatsmeow.Client
	container *sqlstore.Container
	cb        Callback
	startedAt time.Time

	cfgMu sync.RWMutex
	cfg   = config{pack: "ZapFig", author: "", autoImage: true, autoGif: true, maxSeconds: 6}
)

func init() {
	store.SetOSInfo("ZapFig", [3]uint32{1, 0, 0})
}

func getClient() *whatsmeow.Client {
	mu.Lock()
	defer mu.Unlock()
	return client
}

func getConfig() config {
	cfgMu.RLock()
	defer cfgMu.RUnlock()
	return cfg
}

// Configure define o nome do pacote, o autor, a conversão automática e a
// duração máxima (s) das figurinhas animadas.
func Configure(pack, author string, autoImage, autoGif bool, maxSeconds int) {
	if maxSeconds < 1 || maxSeconds > 8 {
		maxSeconds = 6
	}
	cfgMu.Lock()
	cfg = config{pack: strings.TrimSpace(pack), author: strings.TrimSpace(author), autoImage: autoImage, autoGif: autoGif, maxSeconds: maxSeconds}
	cfgMu.Unlock()
}

type cbLogger struct{ mod string }

func (l cbLogger) emit(level, msg string, args ...any) {
	if c := cb; c != nil {
		c.OnLog(fmt.Sprintf("[%s %s] %s", level, l.mod, fmt.Sprintf(msg, args...)))
	}
}
func (l cbLogger) Warnf(m string, a ...any)  { l.emit("aviso", m, a...) }
func (l cbLogger) Errorf(m string, a ...any) { l.emit("erro", m, a...) }
func (l cbLogger) Infof(m string, a ...any)  {}
func (l cbLogger) Debugf(m string, a ...any) {}
func (l cbLogger) Sub(mod string) waLog.Logger {
	return cbLogger{mod: l.mod + "/" + mod}
}

func logf(format string, args ...any) {
	if c := cb; c != nil {
		c.OnLog(fmt.Sprintf(format, args...))
	}
}

func status(s string) {
	if c := cb; c != nil {
		c.OnStatus(s)
	}
}

func openStore(ctx context.Context, dataDir string) (*sqlstore.Container, error) {
	if container != nil {
		return container, nil
	}
	dsn := "file:" + filepath.Join(dataDir, "zapfig.db") + "?_foreign_keys=on&_journal_mode=WAL&_busy_timeout=5000"
	c, err := sqlstore.New(ctx, "sqlite3", dsn, cbLogger{mod: "db"})
	if err != nil {
		return nil, err
	}
	container = c
	return c, nil
}

// IsPaired diz se já existe uma sessão salva em dataDir.
func IsPaired(dataDir string) bool {
	mu.Lock()
	defer mu.Unlock()
	ctx := context.Background()
	c, err := openStore(ctx, dataDir)
	if err != nil {
		return false
	}
	dev, err := c.GetFirstDevice(ctx)
	return err == nil && dev.ID != nil
}

// PairedNumber devolve o número pareado (ou "").
func PairedNumber(dataDir string) string {
	mu.Lock()
	defer mu.Unlock()
	ctx := context.Background()
	c, err := openStore(ctx, dataDir)
	if err != nil {
		return ""
	}
	dev, err := c.GetFirstDevice(ctx)
	if err != nil || dev.ID == nil {
		return ""
	}
	return dev.ID.User
}

// IsRunning diz se o bot está ligado.
func IsRunning() bool { return getClient() != nil }

// Start liga o bot. Se ainda não estiver pareado, phone (só dígitos, com DDI,
// ex: 5516999999999) é usado para pedir um código de pareamento.
func Start(dataDir string, phone string, callback Callback) error {
	mu.Lock()
	defer mu.Unlock()
	cb = callback
	if client != nil {
		return fmt.Errorf("o bot já está ligado")
	}
	startWorker()
	ctx := context.Background()
	c, err := openStore(ctx, dataDir)
	if err != nil {
		return fmt.Errorf("abrir banco: %w", err)
	}
	dev, err := c.GetFirstDevice(ctx)
	if err != nil {
		return fmt.Errorf("ler sessão: %w", err)
	}
	cli := whatsmeow.NewClient(dev, cbLogger{mod: "wa"})
	cli.ManualHistorySyncDownload = true // não baixa histórico: economiza dados e bateria
	cli.AddEventHandler(handleEvent)
	startedAt = time.Now()

	if cli.Store.ID == nil {
		phone = onlyDigits(phone)
		if len(phone) < 10 {
			return fmt.Errorf("informe seu número com DDI e DDD (ex: 5516999999999)")
		}
		qr, err := cli.GetQRChannel(ctx)
		if err != nil {
			return err
		}
		if err := cli.Connect(); err != nil {
			return fmt.Errorf("conectar: %w", err)
		}
		client = cli
		status("Pedindo código de pareamento…")
		go pairLoop(cli, qr, phone)
		return nil
	}

	if err := cli.Connect(); err != nil {
		return fmt.Errorf("conectar: %w", err)
	}
	client = cli
	status("Conectando…")
	return nil
}

func pairLoop(cli *whatsmeow.Client, qr <-chan whatsmeow.QRChannelItem, phone string) {
	asked := false
	for item := range qr {
		switch item.Event {
		case "code":
			if asked {
				continue
			}
			asked = true
			code, err := cli.PairPhone(context.Background(), phone, true, whatsmeow.PairClientChrome, "Chrome (Linux)")
			if err != nil {
				logf("Erro ao pedir código: %v", err)
				status("Falha no pareamento. Confira o número e tente de novo.")
				continue
			}
			if c := cb; c != nil {
				c.OnPairCode(code)
			}
			status("Digite o código no WhatsApp")
		case "success":
			status("Pareado! Conectando…")
		case "timeout":
			status("O código expirou. Desligue e ligue de novo.")
		default:
			if item.Error != nil {
				logf("Pareamento: %v", item.Error)
			}
		}
	}
}

// Stop desliga o bot (a sessão continua salva).
func Stop() {
	mu.Lock()
	cli := client
	client = nil
	mu.Unlock()
	if cli != nil {
		cli.Disconnect()
	}
	status("Desligado")
}

// Logout desconecta este aparelho da conta e apaga a sessão.
// Funciona com o bot ligado ou desligado.
func Logout(dataDir string) error {
	mu.Lock()
	cli := client
	client = nil
	mu.Unlock()
	ctx := context.Background()
	if cli == nil {
		c, err := openStore(ctx, dataDir)
		if err != nil {
			return err
		}
		dev, err := c.GetFirstDevice(ctx)
		if err != nil {
			return err
		}
		if dev.ID == nil {
			return nil
		}
		cli = whatsmeow.NewClient(dev, cbLogger{mod: "wa"})
		if err := cli.Connect(); err != nil {
			// Sem internet: apaga só a sessão local.
			return dev.Delete(ctx)
		}
		time.Sleep(2 * time.Second)
	}
	err := cli.Logout(ctx)
	cli.Disconnect()
	if err != nil {
		_ = cli.Store.Delete(ctx)
	}
	status("Sessão apagada")
	return nil
}

func onlyDigits(s string) string {
	var b strings.Builder
	for _, r := range s {
		if r >= '0' && r <= '9' {
			b.WriteRune(r)
		}
	}
	return b.String()
}

func handleEvent(evt any) {
	switch v := evt.(type) {
	case *events.Connected:
		status("Ligado ✅")
	case *events.Disconnected:
		if getClient() != nil {
			status("Reconectando…")
		}
	case *events.LoggedOut:
		status("Deslogado pelo WhatsApp. Pareie de novo.")
	case *events.TemporaryBan:
		status("Banimento temporário: " + v.String())
	case *events.StreamReplaced:
		status("Sessão aberta em outro lugar. Desligado.")
	case *events.PairSuccess:
		logf("Pareado como %s", v.ID.User)
	case *events.Message:
		enqueue(v)
	}
}
