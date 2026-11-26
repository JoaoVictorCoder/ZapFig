package bot

import (
	"fmt"
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
	cases := []struct {
		in, cmd, arg string
		ok           bool
	}{
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

func TestFitAndAnimated(t *testing.T) {
	if f, ok := fitFor("s2"); !ok || f != "stretch" {
		t.Error("s2")
	}
	if f, ok := fitFor("s"); !ok || f != "" {
		t.Error("s")
	}
	if _, ok := fitFor("img"); ok {
		t.Error("img não é comando de figurinha")
	}
	anim, _ := os.ReadFile("testdata/anim.webp")
	static, _ := os.ReadFile("testdata/lossy_alpha.webp")
	if !isAnimatedWebP(anim) || isAnimatedWebP(static) {
		t.Error("isAnimatedWebP")
	}
}

func TestOggOpusSeconds(t *testing.T) {
	ogg, err := os.ReadFile("testdata/sine3.4.ogg")
	if err != nil {
		t.Fatal(err)
	}
	if s := oggOpusSeconds(ogg); s != 4 {
		t.Errorf("duração = %d, esperado 4 (3,4 s arredondado pra cima)", s)
	}
}

func TestFindURL(t *testing.T) {
	cases := map[string]string{
		"olha isso https://youtu.be/abc123?t=5 kkk": "https://youtu.be/abc123?t=5",
		"(https://www.instagram.com/reel/XYZ/).":    "https://www.instagram.com/reel/XYZ/",
		"sem link aqui":                             "",
		"*https://vm.tiktok.com/ZMabc/*":            "https://vm.tiktok.com/ZMabc/",
	}
	for in, want := range cases {
		if got := findURL(in); got != want {
			t.Errorf("findURL(%q) = %q, want %q", in, got, want)
		}
	}
	if a, ok := isDownloadCmd("mp3"); !a || !ok {
		t.Error("mp3")
	}
	if safeFileName(`a/b:c*?"d`) != "a b c d" {
		t.Errorf("safeFileName = %q", safeFileName(`a/b:c*?"d`))
	}
	if shortErr(fmt.Errorf("blah\nERROR: [youtube] abc: Video unavailable\nmore")) != "[youtube] abc: Video unavailable" {
		t.Error("shortErr")
	}
}

func TestPackPlanAndArgs(t *testing.T) {
	st, tr := segmentPlan(20_000, 6_000, 30)
	if len(st) != 4 || tr { // 0,6,12,18 (sobra de 2s vira a 4ª)
		t.Errorf("plan 20s/6s = %v %v", st, tr)
	}
	st, _ = segmentPlan(18_500, 6_000, 30) // sobra de 0,5s é descartada
	if len(st) != 3 {
		t.Errorf("plan 18.5s/6s = %v", st)
	}
	st, tr = segmentPlan(600_000, 6_000, 30)
	if len(st) != 30 || !tr {
		t.Errorf("limite: %d %v", len(st), tr)
	}
	o := parsePackArgs("4 quadrado Memes do Zé", 6)
	if o.seconds != 4 || o.fit != "stretch" || o.name != "Memes do Zé" {
		t.Errorf("%+v", o)
	}
	o = parsePackArgs("", 6)
	if o.seconds != 6 || o.fit != "" || o.name != "" {
		t.Errorf("%+v", o)
	}
	if o := parsePackArgs("20s", 6); o.seconds != 8 {
		t.Errorf("máx 8s: %+v", o)
	}
	if fmtDur(83_000) != "1:23" {
		t.Error(fmtDur(83_000))
	}
}
