# Third-party notices

Pet Check is MIT licensed. It bundles the following third-party work.

## YAMNet sound classification model

- Files: `app/src/main/assets/yamnet.tflite`, `app/src/main/assets/yamnet_label_list.txt`
- Source: <https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/1/yamnet.tflite>
  (the label list is the file embedded in that model)
- SHA-256 of the model: `4d8b4a53282dc83ef04e3e7dbc4fbc98082e34e44ed798e16c3a0cdd4c584faf`
- Licence: Apache License 2.0, <https://www.apache.org/licenses/LICENSE-2.0>
- YAMNet was trained by Google on the AudioSet dataset.

Library dependencies (AndroidX, Kotlin, LiteRT, Firebase and others) are fetched at build
time and keep their own licences.
