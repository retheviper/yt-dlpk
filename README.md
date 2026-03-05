# yt-dlpk

`yt-dlpk` is a desktop app that helps you save videos and audio with a simple flow.
Paste a link, choose quality, and download.

![yt-dlpk screenshot](./docs/screenshot.png)

Language versions:
- [English](./README.md)
- [Japanese](./README.ja.md)
- [Korean](./README.ko.md)

## What You Can Do

- Analyze a video link and preview title/channel/thumbnail
- Choose video or audio quality formats
- Download as video+audio, video-only, or audio-only
- Save subtitles (when available)
- Download a full playlist or only one item
- Set output folder and filename template
- Track progress and cancel anytime

## How To Use

1. Launch the app.
2. Paste the video URL.
3. Click `Analyze`.
4. Select your preferred format/quality.
5. Set options if needed:
   - subtitles
   - audio extraction format
   - playlist all vs single item
   - output folder and filename
6. Click `Download`.
7. Check progress and cancel if needed.

## Tips

- Start with one short video to confirm your settings.
- Set your output folder first so files are easy to find.
- For playlist links, double-check whether `playlist all` is enabled.

## Notes

- This app internally uses `yt-dlp` and `ffmpeg` for analysis and downloads.
- Official support is for Windows and macOS.
- Linux execution is not guaranteed.
- Availability depends on site support and content status.
- Some videos may be unavailable due to age/region/access restrictions.
