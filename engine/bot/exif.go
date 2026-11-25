package bot

import (
	"bytes"
	"crypto/rand"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
)

// stickerExif monta o bloco EXIF que o WhatsApp lê para mostrar
// "nome do pacote • autor" na figurinha.
func stickerExif(pack, author string) []byte {
	id := make([]byte, 8)
	_, _ = rand.Read(id)
	meta, _ := json.Marshal(map[string]any{
		"sticker-pack-id":        "zapfig." + hex.EncodeToString(id),
		"sticker-pack-name":      pack,
		"sticker-pack-publisher": author,
		"emojis":                 []string{""},
	})
	var b bytes.Buffer
	// Cabeçalho TIFF little-endian + 1 entrada de IFD (tag 0x5741, tipo UNDEFINED).
	b.Write([]byte{0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00, 0x01, 0x00, 0x41, 0x57, 0x07, 0x00})
	_ = binary.Write(&b, binary.LittleEndian, uint32(len(meta)))
	_ = binary.Write(&b, binary.LittleEndian, uint32(0x16)) // offset dos dados
	b.Write(meta)
	return b.Bytes()
}

type riffChunk struct {
	fourcc string
	data   []byte
}

func parseWebP(webp []byte) ([]riffChunk, error) {
	if len(webp) < 12 || string(webp[0:4]) != "RIFF" || string(webp[8:12]) != "WEBP" {
		return nil, errors.New("não é WebP")
	}
	var chunks []riffChunk
	p := 12
	for p+8 <= len(webp) {
		fourcc := string(webp[p : p+4])
		size := int(binary.LittleEndian.Uint32(webp[p+4 : p+8]))
		start := p + 8
		if start+size > len(webp) {
			return nil, errors.New("WebP truncado")
		}
		chunks = append(chunks, riffChunk{fourcc, webp[start : start+size]})
		p = start + size + size%2
	}
	if len(chunks) == 0 {
		return nil, errors.New("WebP vazio")
	}
	return chunks, nil
}

// dimensões e alpha de um WebP simples (VP8 / VP8L), para criar o VP8X.
func simpleInfo(c riffChunk) (w, h int, alpha bool, err error) {
	switch c.fourcc {
	case "VP8L":
		if len(c.data) < 5 || c.data[0] != 0x2f {
			return 0, 0, false, errors.New("VP8L inválido")
		}
		bits := binary.LittleEndian.Uint32(c.data[1:5])
		return int(bits&0x3fff) + 1, int((bits>>14)&0x3fff) + 1, (bits>>28)&1 == 1, nil
	case "VP8 ":
		if len(c.data) < 10 || c.data[3] != 0x9d || c.data[4] != 0x01 || c.data[5] != 0x2a {
			return 0, 0, false, errors.New("VP8 inválido")
		}
		return int(binary.LittleEndian.Uint16(c.data[6:8]) & 0x3fff), int(binary.LittleEndian.Uint16(c.data[8:10]) & 0x3fff), false, nil
	}
	return 0, 0, false, errors.New("formato WebP desconhecido: " + c.fourcc)
}

func put24(b []byte, v int) { b[0], b[1], b[2] = byte(v), byte(v>>8), byte(v>>16) }

// addStickerExif grava pacote/autor dentro do WebP (estático ou animado).
func addStickerExif(webp []byte, pack, author string) ([]byte, error) {
	chunks, err := parseWebP(webp)
	if err != nil {
		return nil, err
	}
	if chunks[0].fourcc != "VP8X" {
		w, h, alpha, err := simpleInfo(chunks[0])
		if err != nil {
			return nil, err
		}
		vp8x := make([]byte, 10)
		if alpha {
			vp8x[0] |= 0x10
		}
		put24(vp8x[4:7], w-1)
		put24(vp8x[7:10], h-1)
		chunks = append([]riffChunk{{"VP8X", vp8x}}, chunks...)
	} else {
		if len(chunks[0].data) < 10 {
			return nil, errors.New("VP8X inválido")
		}
		chunks[0].data = append([]byte(nil), chunks[0].data...)
	}
	chunks[0].data[0] |= 0x08 // flag EXIF

	var out bytes.Buffer
	out.WriteString("RIFF\x00\x00\x00\x00WEBP")
	write := func(c riffChunk) {
		out.WriteString(c.fourcc)
		_ = binary.Write(&out, binary.LittleEndian, uint32(len(c.data)))
		out.Write(c.data)
		if len(c.data)%2 == 1 {
			out.WriteByte(0)
		}
	}
	for _, c := range chunks {
		if c.fourcc != "EXIF" {
			write(c)
		}
	}
	write(riffChunk{"EXIF", stickerExif(pack, author)})
	res := out.Bytes()
	binary.LittleEndian.PutUint32(res[4:8], uint32(len(res)-8))
	return res, nil
}
