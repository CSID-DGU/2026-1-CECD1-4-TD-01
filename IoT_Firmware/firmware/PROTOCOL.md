# P4V1 serial video protocol

The ESP32-P4 splits every Annex-B H.264 access unit into chunks of at most
1024 bytes. Each chunk has a 24-byte little-endian header:

| Offset | Size | Field |
|---:|---:|---|
| 0 | 4 | ASCII magic `P4V1` |
| 4 | 1 | protocol version (`1`) |
| 5 | 1 | flags |
| 6 | 2 | header length (`24`) |
| 8 | 4 | frame sequence |
| 12 | 4 | capture time in milliseconds |
| 16 | 2 | payload length |
| 18 | 2 | reserved (`0`) |
| 20 | 4 | standard CRC-32 of payload |

Flags:

- `0x01`: first chunk of an encoded frame
- `0x02`: last chunk of an encoded frame
- `0x04`: frame contains an H.264 IDR NAL

The stream is self-synchronizing. The Jetson scans for `P4V1`, rejects invalid
headers or CRCs, and waits for an IDR after every reconnect before publishing.

