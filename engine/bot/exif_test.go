package bot

import (
	"os"
	"path/filepath"
	"testing"
)

// Grava cópias com EXIF em testdata/out para conferir com Pillow.
func TestAddStickerExif(t *testing.T) {
	os.MkdirAll("testdata/out", 0o755)
	for _, n := range []string{"lossy", "lossless_alpha", "lossy_alpha", "anim"} {
		in, err := os.ReadFile(filepath.Join("testdata", n+".webp"))
		if err != nil {
			t.Fatal(err)
		}
		out, err := addStickerExif(in, "Meu Pacote", "João")
		if err != nil {
			t.Fatalf("%s: %v", n, err)
		}
		// aplicar duas vezes não pode duplicar o EXIF
		out2, err := addStickerExif(out, "Meu Pacote", "João")
		if err != nil || len(out2) != len(out) {
			t.Fatalf("%s: reaplicar mudou tamanho %d -> %d (%v)", n, len(out), len(out2), err)
		}
		chunks, _ := parseWebP(out)
		if chunks[0].fourcc != "VP8X" || chunks[0].data[0]&0x08 == 0 {
			t.Fatalf("%s: VP8X/flag EXIF ausente", n)
		}
		os.WriteFile(filepath.Join("testdata/out", n+".webp"), out, 0o644)
	}
}

func TestParseCommand(t *testing.T) {
	cases := []struct{ in, cmd, arg string; ok bool }{
		{"/s", "s", "", true},
		{"  /T  Bom dia  ", "t", "Bom dia", true},
		{"/pack Memes | João", "pack", "Memes | João", true},
		{"oi /s", "", "", false},
		{"/", "", "", false},
	}
	for _, c := range cases {
		cmd, arg, ok := parseCommand(c.in)
		if cmd != c.cmd || arg != c.arg || ok != c.ok {
			t.Errorf("%q => %q %q %v", c.in, cmd, arg, ok)
		}
	}
	p, a, h := parsePack("Memes | João")
	if p != "Memes" || a != "João" || !h {
		t.Error("parsePack")
	}
	if p, _, h := parsePack("Só nome"); p != "Só nome" || h {
		t.Error("parsePack sem autor")
	}
}
