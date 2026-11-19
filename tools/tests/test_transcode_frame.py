import base64
from compression import zstd
import hashlib
from pathlib import Path
import sys
import unittest
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from transcode_frame import STANDARD, alphabet_bytes, decode_field, encode_field, transcode


class FrameTests(unittest.TestCase):
    def test_single_frame_preserves_bytes(self):
        for raw in (b"", bytes(range(256)), b"\xff\x80\x00" * 10000):
            with self.subTest(length=len(raw)):
                result, digest = transcode(zstd.compress(raw))
                restored = zlib.decompressobj()
                self.assertEqual(raw, restored.decompress(result))
                self.assertTrue(restored.eof)
                self.assertFalse(restored.unused_data)
                self.assertEqual(hashlib.sha256(raw).hexdigest(), digest)

    def test_rejects_wrong_or_incomplete_stream(self):
        frame = zstd.compress(b"text")
        checksummed = bytearray(zstd.compress(b"sample", options={zstd.CompressionParameter.checksum_flag: 1}))
        checksummed[-1] ^= 1
        for value in (b"", zlib.compress(b"text"), frame[:-1], frame * 2, frame + b"x", bytes(checksummed)):
            with self.subTest(value=value), self.assertRaises((ValueError, zstd.ZstdError)):
                transcode(value)

    def test_limit_applies_to_declared_and_unknown_size(self):
        for content_size in (0, 1):
            frame = zstd.compress(b"A" * 257, options={zstd.CompressionParameter.content_size_flag: content_size})
            with self.assertRaises(ValueError):
                transcode(frame, limit=256)
            self.assertEqual(b"A" * 257, zlib.decompress(transcode(frame, limit=257)[0]))

    def test_custom_alphabet_decodes_all_residues(self):
        alpha = alphabet_bytes(STANDARD[::-1].decode())
        for size in range(1, 257):
            data = bytes(range(size))
            text = base64.b64encode(data).rstrip(b"=").translate(bytes.maketrans(STANDARD, alpha))
            self.assertEqual(text, encode_field(data, alpha))
            self.assertEqual(data, decode_field(text, alpha))

    def test_noncanonical_text_is_rejected(self):
        for value in (b"", b"A", b"AB", b"AA=", b"AA\n", b"A\xff"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                decode_field(value, STANDARD)
        for alpha in ("a" * 64, STANDARD.decode()[:-1], " " + STANDARD.decode()[1:]):
            with self.assertRaises(ValueError):
                alphabet_bytes(alpha)


if __name__ == "__main__":
    unittest.main()
