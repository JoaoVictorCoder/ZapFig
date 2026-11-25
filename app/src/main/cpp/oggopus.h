#ifndef ZAPFIG_OGGOPUS_H
#define ZAPFIG_OGGOPUS_H
#include <stddef.h>
#include <stdint.h>
// Codifica PCM 16 bits mono 48 kHz em OGG Opus. Devolve 0 se ok; *out deve ser liberado com free().
int oggopus_encode(const int16_t *pcm, int samples, int bitrate, uint8_t **out, size_t *out_len);
#endif
