# Third-party notices

Pet Check is MIT licensed. It bundles the following third-party work.

## YAMNet sound classification model

- Files: `app/src/main/assets/yamnet.tflite`, `app/src/main/assets/yamnet_label_list.txt`
- Source: <https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/1/yamnet.tflite>
  (the label list is the file embedded in that model)
- SHA-256 of the model: `4d8b4a53282dc83ef04e3e7dbc4fbc98082e34e44ed798e16c3a0cdd4c584faf`
- Licence: Apache License 2.0, <https://www.apache.org/licenses/LICENSE-2.0>
- YAMNet was trained by Google on the AudioSet dataset.

## Test recording of a dog bark

- File: `testdata/dog_bark.wav`, used only by tests and not shipped in the app
- Derived from clip `1-100032-A-0.wav` of the ESC-10 subset of
  [ESC-50](https://github.com/karolpiczak/ESC-50) by Karol J. Piczak, which is licensed
  [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/). It was resampled to 16 kHz and
  trimmed.
- That clip was in turn derived from "rose_bark.wav" by nfrae on Freesound
  (<http://www.freesound.org/people/nfrae/sounds/100032/>), released as CC0.

Library dependencies (AndroidX, Kotlin, LiteRT, Firebase and others) are fetched at build
time and keep their own licences.
