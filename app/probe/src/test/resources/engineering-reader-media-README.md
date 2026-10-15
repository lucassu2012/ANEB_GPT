# Anonymous engineering Reader test media

These two files are synthetic codec fixtures, not observations. No chat, account,
device, network, research session or real R21 pixels are included.

| File | Bytes | SHA-256 |
|---|---:|---|
| engineering-reader-sample.mp4 | 1111 | 6b540bda42094b20f6fcf43ed9da52c299da8ea2937aaab74543f36618d0a397 |
| engineering-reader-sample-c1.mp4 | 1116 | 3d552791c1405173dd46421aa5d2b1ad8caf6acda9da1993298d0171356784b7 |

Generated offline with OpenCV 4.13.0 `VideoWriter`, fourcc `mp4v`, 32×32,
8 fps, 8 frames, no audio. Frame index `i` is 0 through 7, pixels are BGR:
W1 `(i*24,160,64)`; C1 `(64,i*24,200)`. Both writers actually opened,
were released, and both outputs were read sequentially by `VideoCapture`:
8 decoded frames each, each frame 32×32. Codec versions may change output bytes;
these checked-in bytes/pins are authoritative, not a bit-reproducibility promise.

The public Reader/store test uses their different bytes to detect wrong-condition
attachment/reopen. It does not claim Android playback or rendered frames.
PNG fixtures are generated separately through Android Bitmap in the test:
2×2 solid red W1, 2×2 solid blue C1, genuine PNG compression and decoding.
No runtime dependency on OpenCV is added to the Android product or its tests.
