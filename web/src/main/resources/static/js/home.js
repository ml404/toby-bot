function toggleNav() {
    const menu = document.getElementById('nav-menu');
    if (!menu) return false;
    return menu.classList.toggle('open');
}

// Click-toggle for navbar dropdowns. Desktop also opens on :hover via CSS;
// click is the keyboard/touch path. Closing any other open dropdown keeps
// only one expanded at a time.
function toggleDropdown(toggle) {
    if (!toggle) return false;
    const dropdown = toggle.closest('.nav-dropdown');
    if (!dropdown) return false;
    const willOpen = !dropdown.classList.contains('open');
    closeAllDropdowns();
    if (willOpen) {
        dropdown.classList.add('open');
        toggle.setAttribute('aria-expanded', 'true');
    }
    return willOpen;
}

function closeAllDropdowns() {
    const open = document.querySelectorAll('.nav-dropdown.open');
    open.forEach(function (d) {
        d.classList.remove('open');
        const t = d.querySelector('.nav-dropdown-toggle');
        if (t) t.setAttribute('aria-expanded', 'false');
    });
}

if (typeof document !== 'undefined' && document.addEventListener) {
    // Click outside any dropdown collapses them all. Doesn't interfere with
    // clicks on dropdown menu items themselves — those navigate to a new
    // page, which discards the document anyway.
    document.addEventListener('click', function (e) {
        if (e.target && e.target.closest && e.target.closest('.nav-dropdown')) return;
        closeAllDropdowns();
    });
    // Esc closes any open dropdown.
    document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape') closeAllDropdowns();
    });
}

if (typeof module !== 'undefined') {
    module.exports = { toggleNav, toggleDropdown, closeAllDropdowns };
}
