function toggleInput(type) {
    document.getElementById('urlSection').style.display = (type === 'url') ? 'block' : 'none';
    document.getElementById('fileSection').style.display = (type === 'file') ? 'block' : 'none';
}

function togglePlay(btn) {
    const audioId = btn.dataset.audioId;
    const audio = document.getElementById(audioId);
    if (audio.paused) {
        document.querySelectorAll('audio').forEach(a => { a.pause(); a.currentTime = 0; });
        document.querySelectorAll('.btn-play').forEach(b => { b.textContent = '▶'; b.classList.remove('playing'); });
        audio.play();
        btn.textContent = '⏹';
        btn.classList.add('playing');
    } else {
        audio.pause();
        audio.currentTime = 0;
        btn.textContent = '▶';
        btn.classList.remove('playing');
    }
}

if (typeof module !== 'undefined') {
    module.exports = { toggleInput, togglePlay };
}
