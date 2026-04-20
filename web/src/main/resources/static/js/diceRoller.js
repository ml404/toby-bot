(function () {
    const toggle = document.getElementById('dice-toggle');
    const popover = document.getElementById('dice-popover');
    if (!toggle || !popover) return;

    const cancelBtn = document.getElementById('dice-cancel');
    const formulaEl = document.getElementById('dice-formula');
    const countInput = document.getElementById('dice-count');
    const sidesInput = document.getElementById('dice-sides');
    const modInput = document.getElementById('dice-mod');
    const modValueEl = document.getElementById('mod-value');
    const modMinus = document.getElementById('mod-minus');
    const modPlus = document.getElementById('mod-plus');
    const expressionInput = document.getElementById('dice-expression');

    const dieButtons = popover.querySelectorAll('.chip-btn[data-die]');
    const countButtons = popover.querySelectorAll('.chip-btn[data-count]');

    const MAX_MOD = 50;
    let die = 20;
    let count = 1;
    let modifier = 0;

    function refreshFormula() {
        const modStr = modifier === 0 ? '' : (modifier > 0 ? '+' + modifier : String(modifier));
        formulaEl.textContent = count + 'd' + die + modStr;
        countInput.value = String(count);
        sidesInput.value = String(die);
        modInput.value = String(modifier);
        modValueEl.textContent = modifier >= 0 ? '+' + modifier : String(modifier);
        modMinus.disabled = modifier <= -MAX_MOD;
        modPlus.disabled = modifier >= MAX_MOD;
    }

    function selectDie(value) {
        die = value;
        dieButtons.forEach(function (b) {
            b.classList.toggle('selected', parseInt(b.dataset.die, 10) === value);
        });
        refreshFormula();
    }

    function selectCount(value) {
        count = value;
        countButtons.forEach(function (b) {
            b.classList.toggle('selected', parseInt(b.dataset.count, 10) === value);
        });
        refreshFormula();
    }

    dieButtons.forEach(function (b) {
        b.addEventListener('click', function () { selectDie(parseInt(b.dataset.die, 10)); });
    });
    countButtons.forEach(function (b) {
        b.addEventListener('click', function () { selectCount(parseInt(b.dataset.count, 10)); });
    });

    modMinus.addEventListener('click', function () {
        if (modifier > -MAX_MOD) { modifier -= 1; refreshFormula(); }
    });
    modPlus.addEventListener('click', function () {
        if (modifier < MAX_MOD) { modifier += 1; refreshFormula(); }
    });

    function openPopover() {
        popover.classList.add('open');
        toggle.setAttribute('aria-expanded', 'true');
    }
    function closePopover() {
        popover.classList.remove('open');
        toggle.setAttribute('aria-expanded', 'false');
    }

    toggle.addEventListener('click', function (e) {
        e.stopPropagation();
        if (popover.classList.contains('open')) closePopover(); else openPopover();
    });
    cancelBtn.addEventListener('click', closePopover);

    // Dismiss when clicking outside the popover.
    document.addEventListener('click', function (e) {
        if (!popover.classList.contains('open')) return;
        if (popover.contains(e.target) || toggle.contains(e.target)) return;
        closePopover();
    });

    // ESC closes.
    document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape' && popover.classList.contains('open')) closePopover();
    });

    // Clear expression whenever any picker changes so matrix values win by default;
    // and clear picker-derived hidden inputs when the expression box is non-empty
    // so the server sees only the expression.
    function onPickerChange() {
        if (expressionInput.value.trim() !== '') expressionInput.value = '';
    }
    dieButtons.forEach(function (b) { b.addEventListener('click', onPickerChange); });
    countButtons.forEach(function (b) { b.addEventListener('click', onPickerChange); });
    modMinus.addEventListener('click', onPickerChange);
    modPlus.addEventListener('click', onPickerChange);

    refreshFormula();
})();
