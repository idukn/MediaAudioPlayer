const express = require('express');
const { spawn } = require('child_process');
const fs = require('fs');

const app = express();

app.get('/preview', (req, res) => {
  const url = req.query.url;
  if (!url) return res.send('no url');

  res.setHeader('Content-Type', 'audio/mpeg');
  res.setHeader('Transfer-Encoding', 'chunked');

  console.log('Spawning yt-dlp...');
  const ytdlp = spawn('yt-dlp', ['-o', '-', '-f', 'bestaudio/best', url]);
  
  console.log('Spawning ffmpeg...');
  const ffmpeg = spawn('ffmpeg', [
    '-i', 'pipe:0',
    '-vn',
    '-c:a', 'libmp3lame',
    '-b:a', '192k',
    '-f', 'mp3',
    'pipe:1'
  ]);

  ytdlp.stdout.pipe(ffmpeg.stdin);
  
  // write to a local file
  const fileStream = fs.createWriteStream('downloaded_test.mp3');
  ffmpeg.stdout.pipe(fileStream);

  // pipe to network
  ffmpeg.stdout.pipe(res);

  ffmpeg.stderr.on('data', d => console.log('ffmpeg:', d.toString().slice(0, 100)));

  ffmpeg.on('close', () => {
    console.log('DONE!');
    res.end();
  });
});

app.listen(18080, () => {
  console.log('Listening on http://localhost:18080/preview?url=... test with browser.');
});
