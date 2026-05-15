const { execSync } = require('child_process');

function runCommand(command) {
  try {
    const stdout = execSync(command, { encoding: 'utf8', maxBuffer: 1024 * 1024 * 10 });
    return stdout;
  } catch (err) {
    console.error(err.message);
    return '';
  }
}

console.log("Testing yt-dlp search for 人マニア...");
const ytdlpOut = runCommand(`yt-dlp "ytsearch10:人マニア" --dump-json --flat-playlist`);
const ytItems = ytdlpOut.trim().split('\\n').filter(Boolean).map(JSON.parse);
console.log("YouTube Supplements:");
ytItems.forEach((item, i) => {
    console.log(`[${i}] ${item.title} (Views: ${item.view_count})`);
});
