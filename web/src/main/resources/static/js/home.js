function toggleNav() {
    const menu = document.getElementById('nav-menu');
    if (!menu) return false;
    return menu.classList.toggle('open');
}

if (typeof module !== 'undefined') {
    module.exports = { toggleNav };
}
