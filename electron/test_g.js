const { spawn } = require('child_process');

function runYtDlpLocal(url) {
  return new Promise((resolve, reject) => {
    const child = spawn('yt-dlp', ['-f', 'bestaudio/best', '-g', url]);
    let out = '';
    child.stdout.on('data', d => out += d.toString());
    child.on('close', code => {
      if (code === 0) resolve(out.trim());
      else reject(new Error('failed'));
    });
  });
}

(async () => {
  try {
    const ytUrl = 'https://www.youtube.com/watch?v=yW1yptzSksE'; // dummy
    console.log('YouTube URL:', await runYtDlpLocal(ytUrl));

    const nicoUrl = 'https://www.nicovideo.jp/watch/sm42715449'; // 人マニア
    console.log('Nico URL:', await runYtDlpLocal(nicoUrl));
  } catch (e) {
    console.error(e);
  }
})();
