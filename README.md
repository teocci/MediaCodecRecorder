## MediaCodec Video Recorder

This repository contains a simple implementation of a video recorder using [`MediaCodec` class](https://developer.android.com/reference/android/media/MediaCodec.html).

`MediaCodec` class can be used to access low-level media encoder/decoder components. This class is part of the Android low-level multimedia support infrastructure, which normally has been used together with `MediaExtractor`, `MediaSync`, `MediaMuxer`, `MediaCrypto`, `MediaDrm`, `Image`, `Surface`, and `AudioTrack`.

This app records video while a user is pressing the recording button. Users can pause and continue whit the video recording after any interval of time.

### Disclaimer
This repository contains a simple sample code intended to demonstrate the capabilities of the `MediaCodec` class. It is not intended to be used as-is in applications as a library dependency, and will not be maintained as such. Bug fix contributions are welcome, but issues and feature requests will not be addressed.

### Example Contents
These sample application has implemented these features:

* Pause and resume when recording
* Crop and scale the output video to designated size.

## Credits
* [Android MediaCodec stuff][1]
* [EncodeAndMuxTest][2]
* [VideoHacks][3]

### Pre-requisites
    
- Android SDK 25
- Android Build Tools v25.0.2
- Android Support Repository

## License

The code supplied here is covered under the MIT Open Source License.

[1]: http://bigflake.com/mediacodec/
[2]: http://bigflake.com/mediacodec/EncodeAndMuxTest.java.txt
[3]: https://github.com/teocci/VideoHacks
[4]: https://github.com/sourab-sharma/TouchToRecord
[5]: https://github.com/qdrzwd/VideoRecorder
