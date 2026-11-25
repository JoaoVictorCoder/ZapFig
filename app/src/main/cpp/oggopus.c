// PCM mono 48 kHz -> arquivo OGG Opus (RFC 7845), usando libopus.
// Sem dependência de Android: o mesmo código é testado no PC.
#include "oggopus.h"
#include <stdlib.h>
#include <string.h>
#include "opus.h"

#define RATE 48000
#define FRAME 960          // 20 ms
#define MAX_PACKET 4000

typedef struct { uint8_t *d; size_t n, cap; } Buf;

static int buf_put(Buf *b, const void *p, size_t n) {
    if (b->n + n > b->cap) {
        size_t c = b->cap ? b->cap : 65536;
        while (c < b->n + n) c *= 2;
        uint8_t *nd = realloc(b->d, c);
        if (!nd) return 0;
        b->d = nd; b->cap = c;
    }
    memcpy(b->d + b->n, p, n);
    b->n += n;
    return 1;
}

static uint32_t crc_table[256];
static void crc_init(void) {
    static int done = 0;
    if (done) return;
    for (uint32_t i = 0; i < 256; i++) {
        uint32_t r = i << 24;
        for (int k = 0; k < 8; k++) r = (r & 0x80000000u) ? (r << 1) ^ 0x04c11db7u : r << 1;
        crc_table[i] = r;
    }
    done = 1;
}
static uint32_t ogg_crc(const uint8_t *p, size_t n) {
    uint32_t c = 0;
    for (size_t i = 0; i < n; i++) c = (c << 8) ^ crc_table[((c >> 24) & 0xff) ^ p[i]];
    return c;
}

static void le32(uint8_t *p, uint32_t v) { p[0] = v; p[1] = v >> 8; p[2] = v >> 16; p[3] = v >> 24; }
static void le64(uint8_t *p, uint64_t v) { le32(p, (uint32_t) v); le32(p + 4, (uint32_t) (v >> 32)); }

// Escreve uma página com vários pacotes (cada um < 255*255 bytes).
static int write_page(Buf *out, uint32_t serial, uint32_t seq, uint8_t flags, uint64_t granule,
                      const uint8_t **pkts, const int *lens, int count) {
    uint8_t lacing[255];
    int nseg = 0;
    size_t body = 0;
    for (int i = 0; i < count; i++) {
        int l = lens[i];
        while (l >= 255) { lacing[nseg++] = 255; l -= 255; }
        lacing[nseg++] = (uint8_t) l;
        body += lens[i];
    }
    size_t hlen = 27 + nseg;
    uint8_t *page = malloc(hlen + body);
    if (!page) return 0;
    memcpy(page, "OggS", 4);
    page[4] = 0;          // versão
    page[5] = flags;      // 0x02 = início, 0x04 = fim
    le64(page + 6, granule);
    le32(page + 14, serial);
    le32(page + 18, seq);
    le32(page + 22, 0);   // CRC (calculado abaixo)
    page[26] = (uint8_t) nseg;
    memcpy(page + 27, lacing, nseg);
    size_t off = hlen;
    for (int i = 0; i < count; i++) { memcpy(page + off, pkts[i], lens[i]); off += lens[i]; }
    le32(page + 22, ogg_crc(page, hlen + body));
    int ok = buf_put(out, page, hlen + body);
    free(page);
    return ok;
}

int oggopus_encode(const int16_t *pcm, int samples, int bitrate, uint8_t **out, size_t *out_len) {
    crc_init();
    *out = NULL; *out_len = 0;
    if (samples <= 0) return -1;

    int err = 0;
    OpusEncoder *enc = opus_encoder_create(RATE, 1, OPUS_APPLICATION_AUDIO, &err);
    if (err != OPUS_OK || !enc) return -2;
    opus_encoder_ctl(enc, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(8));
    opus_int32 lookahead = 0;
    opus_encoder_ctl(enc, OPUS_GET_LOOKAHEAD(&lookahead));
    const uint16_t preskip = (uint16_t) lookahead;

    Buf b = {0};
    const uint32_t serial = 0x5A415046; // "ZAPF"
    uint32_t seq = 0;
    int ok = 1;

    // Página 1: OpusHead
    uint8_t head[19];
    memcpy(head, "OpusHead", 8);
    head[8] = 1;                       // versão
    head[9] = 1;                       // canais
    head[10] = preskip & 0xff; head[11] = preskip >> 8;
    le32(head + 12, RATE);             // taxa original
    head[16] = 0; head[17] = 0;        // ganho
    head[18] = 0;                      // mapeamento de canais
    const uint8_t *p0 = head; int l0 = sizeof head;
    ok = ok && write_page(&b, serial, seq++, 0x02, 0, &p0, &l0, 1);

    // Página 2: OpusTags
    const char *vendor = "ZapFig";
    uint8_t tags[8 + 4 + 6 + 4];
    memcpy(tags, "OpusTags", 8);
    le32(tags + 8, (uint32_t) strlen(vendor));
    memcpy(tags + 12, vendor, 6);
    le32(tags + 18, 0);                // nenhum comentário
    const uint8_t *p1 = tags; int l1 = sizeof tags;
    ok = ok && write_page(&b, serial, seq++, 0x00, 0, &p1, &l1, 1);

    // Páginas de áudio: até 50 pacotes (1 s) por página.
    const int frames = (samples + FRAME - 1) / FRAME;
    int16_t frame[FRAME];
    uint8_t *pkt_store = malloc((size_t) 50 * MAX_PACKET);
    const uint8_t *pkts[50];
    int lens[50];
    int inpage = 0;
    uint64_t granule = preskip;
    const uint64_t final_granule = (uint64_t) preskip + (uint64_t) samples;
    if (!pkt_store) ok = 0;

    for (int f = 0; ok && f < frames; f++) {
        int start = f * FRAME;
        int n = samples - start < FRAME ? samples - start : FRAME;
        memcpy(frame, pcm + start, (size_t) n * sizeof(int16_t));
        if (n < FRAME) memset(frame + n, 0, (size_t) (FRAME - n) * sizeof(int16_t));
        uint8_t *dst = pkt_store + (size_t) inpage * MAX_PACKET;
        int len = opus_encode(enc, frame, FRAME, dst, MAX_PACKET);
        if (len < 0) { ok = 0; break; }
        pkts[inpage] = dst; lens[inpage] = len; inpage++;
        granule += FRAME;
        int last = (f == frames - 1);
        if (inpage == 50 || last) {
            uint64_t g = last ? final_granule : granule;   // última página corta o enchimento
            ok = write_page(&b, serial, seq++, last ? 0x04 : 0x00, g, pkts, lens, inpage);
            inpage = 0;
        }
    }
    free(pkt_store);
    opus_encoder_destroy(enc);
    if (!ok) { free(b.d); return -3; }
    *out = b.d;
    *out_len = b.n;
    return 0;
}
