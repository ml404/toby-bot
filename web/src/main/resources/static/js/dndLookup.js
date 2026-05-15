(function () {
    'use strict';

    const API_BASE = 'https://www.dnd5eapi.co/api';
    const form = document.querySelector('[data-form="lookup"]');
    if (!form) return;
    const statusEl = form.parentElement.querySelector('[data-status]');
    const suggestionsEl = form.parentElement.querySelector('[data-suggestions]');
    const resultEl = form.parentElement.querySelector('[data-result]');
    const queryInput = form.querySelector('[data-input="query"]');
    const typeSelect = form.querySelector('select[name="type"]');
    const autocompleteEl = form.querySelector('[data-autocomplete]');

    const TYPE_LABELS = {
        'spells': 'spell',
        'monsters': 'monster',
        'classes': 'class',
        'subclasses': 'subclass',
        'races': 'race',
        'subraces': 'subrace',
        'conditions': 'condition',
        'rule-sections': 'rule',
        'features': 'feature',
        'traits': 'trait',
        'equipment': 'equipment',
        'equipment-categories': 'equipment category',
        'weapon-properties': 'weapon property',
        'ability-scores': 'ability score',
        'skills': 'skill',
        'proficiencies': 'proficiency',
        'languages': 'language',
        'magic-schools': 'magic school',
        'damage-types': 'damage type'
    };

    let autocompleteController = null;
    let autocompleteTimer = null;
    let activeSuggestionIndex = -1;
    let currentSuggestions = [];

    function slug(s) {
        return s.trim().toLowerCase().replace(/\s+/g, '-');
    }

    function clear() {
        statusEl.textContent = '';
        suggestionsEl.hidden = true;
        suggestionsEl.replaceChildren();
        resultEl.hidden = true;
        resultEl.replaceChildren();
    }

    function setStatus(msg) {
        statusEl.textContent = msg;
    }

    function hideAutocomplete() {
        autocompleteEl.hidden = true;
        autocompleteEl.replaceChildren();
        currentSuggestions = [];
        activeSuggestionIndex = -1;
        queryInput.setAttribute('aria-expanded', 'false');
        queryInput.removeAttribute('aria-activedescendant');
    }

    function renderAutocomplete(suggestions) {
        autocompleteEl.replaceChildren();
        currentSuggestions = suggestions;
        activeSuggestionIndex = -1;
        if (!suggestions.length) {
            hideAutocomplete();
            return;
        }
        suggestions.forEach((s, i) => {
            const li = document.createElement('li');
            li.id = `lookup-ac-${i}`;
            li.className = 'lookup-autocomplete-item';
            li.setAttribute('role', 'option');
            li.textContent = s.name;
            li.addEventListener('mousedown', (e) => {
                e.preventDefault();
                pickSuggestion(s);
            });
            autocompleteEl.appendChild(li);
        });
        autocompleteEl.hidden = false;
        queryInput.setAttribute('aria-expanded', 'true');
    }

    function pickSuggestion(suggestion) {
        queryInput.value = suggestion.name;
        hideAutocomplete();
        form.requestSubmit();
    }

    function setActiveSuggestion(index) {
        const items = autocompleteEl.querySelectorAll('.lookup-autocomplete-item');
        items.forEach(i => i.classList.remove('is-active'));
        if (index < 0 || index >= items.length) {
            activeSuggestionIndex = -1;
            queryInput.removeAttribute('aria-activedescendant');
            return;
        }
        activeSuggestionIndex = index;
        items[index].classList.add('is-active');
        queryInput.setAttribute('aria-activedescendant', items[index].id);
        items[index].scrollIntoView({ block: 'nearest' });
    }

    async function fetchAutocomplete(type, input) {
        if (autocompleteController) autocompleteController.abort();
        autocompleteController = new AbortController();
        const signal = autocompleteController.signal;
        const url = input
            ? `${API_BASE}/${type}?name=${encodeURIComponent(input)}`
            : `${API_BASE}/${type}`;
        try {
            const r = await fetch(url, { signal });
            if (!r.ok) return [];
            const data = await r.json();
            return Array.isArray(data.results) ? data.results.slice(0, 25) : [];
        } catch (err) {
            if (err.name === 'AbortError') return null;
            return [];
        }
    }

    function scheduleAutocomplete() {
        if (autocompleteTimer) clearTimeout(autocompleteTimer);
        autocompleteTimer = setTimeout(async () => {
            const type = typeSelect.value;
            const input = queryInput.value.trim();
            const suggestions = await fetchAutocomplete(type, input);
            if (suggestions === null) return; // aborted
            renderAutocomplete(suggestions);
        }, 150);
    }

    function renderResult(type, data) {
        resultEl.replaceChildren();
        const title = document.createElement('h2');
        title.textContent = data.name || '(unnamed)';
        resultEl.appendChild(title);

        const renderer = TYPE_RENDERERS[type] || renderGeneric;
        renderer(data);

        resultEl.hidden = false;
    }

    function appendMeta(parts) {
        if (!parts.length) return;
        const meta = document.createElement('p');
        meta.className = 'muted';
        meta.textContent = parts.join(' • ');
        resultEl.appendChild(meta);
    }

    function appendParagraphs(items) {
        items.forEach(p => {
            if (p == null) return;
            const para = document.createElement('p');
            para.textContent = String(p);
            resultEl.appendChild(para);
        });
    }

    function appendSection(heading, items) {
        if (!items || !items.length) return;
        const h = document.createElement('h3');
        h.textContent = heading;
        resultEl.appendChild(h);
        appendParagraphs(items);
    }

    function appendField(label, value) {
        if (value == null || value === '') return;
        const p = document.createElement('p');
        const strong = document.createElement('strong');
        strong.textContent = `${label}: `;
        p.appendChild(strong);
        p.appendChild(document.createTextNode(String(value)));
        resultEl.appendChild(p);
    }

    function descArray(data) {
        return Array.isArray(data.desc) ? data.desc
            : Array.isArray(data.description) ? data.description
            : data.desc ? [data.desc]
            : [];
    }

    function renderGeneric(data) {
        appendParagraphs(descArray(data));
    }

    function renderSpell(data) {
        const parts = [];
        if (data.level !== undefined) parts.push(`Level ${data.level}`);
        if (data.school?.name) parts.push(data.school.name);
        if (data.casting_time) parts.push(`Cast: ${data.casting_time}`);
        if (data.range) parts.push(`Range: ${data.range}`);
        if (data.duration) parts.push(`Duration: ${data.duration}`);
        appendMeta(parts);
        appendParagraphs(descArray(data));
        appendSection('At higher levels', data.higher_level);
    }

    function renderMonster(data) {
        const header = [data.size, data.type, data.alignment].filter(Boolean).join(', ');
        if (header) appendMeta([header]);

        const ac = Array.isArray(data.armor_class) && data.armor_class[0]
            ? `${data.armor_class[0].value}${data.armor_class[0].type ? ' (' + data.armor_class[0].type + ')' : ''}`
            : null;
        appendField('AC', ac);
        if (data.hit_points != null) {
            appendField('HP', data.hit_dice ? `${data.hit_points} (${data.hit_dice})` : data.hit_points);
        }
        if (data.speed) {
            const parts = Object.entries(data.speed).map(([k, v]) => `${k} ${v}`);
            if (parts.length) appendField('Speed', parts.join(', '));
        }
        const abilities = ['strength', 'dexterity', 'constitution', 'intelligence', 'wisdom', 'charisma']
            .filter(k => data[k] != null)
            .map(k => `${k.slice(0, 3).toUpperCase()} ${data[k]}`);
        if (abilities.length) appendField('Ability Scores', abilities.join(', '));

        if (data.challenge_rating != null) {
            appendField('Challenge', `${data.challenge_rating}${data.xp ? ' (' + data.xp + ' XP)' : ''}`);
        }
        if (data.languages) appendField('Languages', data.languages);
        if (Array.isArray(data.damage_immunities) && data.damage_immunities.length)
            appendField('Damage Immunities', data.damage_immunities.join(', '));
        if (Array.isArray(data.damage_resistances) && data.damage_resistances.length)
            appendField('Damage Resistances', data.damage_resistances.join(', '));
        if (Array.isArray(data.damage_vulnerabilities) && data.damage_vulnerabilities.length)
            appendField('Vulnerabilities', data.damage_vulnerabilities.join(', '));

        renderActionList('Special Abilities', data.special_abilities);
        renderActionList('Actions', data.actions);
        renderActionList('Legendary Actions', data.legendary_actions);
    }

    function renderActionList(heading, list) {
        if (!Array.isArray(list) || !list.length) return;
        const h = document.createElement('h3');
        h.textContent = heading;
        resultEl.appendChild(h);
        list.forEach(a => {
            const p = document.createElement('p');
            const strong = document.createElement('strong');
            strong.textContent = `${a.name}: `;
            p.appendChild(strong);
            p.appendChild(document.createTextNode(a.desc || ''));
            resultEl.appendChild(p);
        });
    }

    function renderClass(data) {
        if (data.hit_die != null) appendField('Hit Die', `d${data.hit_die}`);
        if (Array.isArray(data.saving_throws) && data.saving_throws.length)
            appendField('Saving Throws', data.saving_throws.map(s => s.name).join(', '));
        if (Array.isArray(data.proficiencies) && data.proficiencies.length)
            appendField('Proficiencies', data.proficiencies.map(p => p.name).join(', '));
        if (Array.isArray(data.subclasses) && data.subclasses.length)
            appendField('Subclasses', data.subclasses.map(s => s.name).join(', '));
        if (data.spellcasting?.spellcasting_ability?.name)
            appendField('Spellcasting', data.spellcasting.spellcasting_ability.name);
    }

    function renderRace(data) {
        if (data.speed != null) appendField('Speed', `${data.speed} ft`);
        if (data.size) appendField('Size', data.size);
        if (data.alignment) appendField('Alignment', data.alignment);
        if (data.age) appendField('Age', data.age);
        if (Array.isArray(data.ability_bonuses) && data.ability_bonuses.length) {
            const text = data.ability_bonuses.map(b => `${b.ability_score?.name || '?'}: +${b.bonus}`).join(', ');
            appendField('Ability Bonuses', text);
        }
        if (Array.isArray(data.languages) && data.languages.length)
            appendField('Languages', data.languages.map(l => l.name).join(', '));
        if (Array.isArray(data.traits) && data.traits.length)
            appendField('Traits', data.traits.map(t => t.name).join(', '));
        if (Array.isArray(data.subraces) && data.subraces.length)
            appendField('Subraces', data.subraces.map(s => s.name).join(', '));
    }

    function renderSubrace(data) {
        appendParagraphs(data.desc ? [data.desc] : []);
        if (data.race?.name) appendField('Parent Race', data.race.name);
        if (Array.isArray(data.ability_bonuses) && data.ability_bonuses.length) {
            const text = data.ability_bonuses.map(b => `${b.ability_score?.name || '?'}: +${b.bonus}`).join(', ');
            appendField('Ability Bonuses', text);
        }
        if (Array.isArray(data.racial_traits) && data.racial_traits.length)
            appendField('Racial Traits', data.racial_traits.map(t => t.name).join(', '));
    }

    function renderEquipment(data) {
        if (data.equipment_category?.name) appendField('Category', data.equipment_category.name);
        if (data.cost) appendField('Cost', `${data.cost.quantity} ${data.cost.unit}`);
        if (data.weight != null) appendField('Weight', `${data.weight} lb`);
        if (data.damage) {
            appendField('Damage', `${data.damage.damage_dice || ''} ${data.damage.damage_type?.name || ''}`.trim());
        }
        if (data.two_handed_damage) {
            appendField('Two-Handed Damage', `${data.two_handed_damage.damage_dice || ''} ${data.two_handed_damage.damage_type?.name || ''}`.trim());
        }
        if (data.range) {
            appendField('Range', data.range.long ? `${data.range.normal}/${data.range.long}` : `${data.range.normal}`);
        }
        if (data.armor_category) appendField('Armor Category', data.armor_category);
        if (data.armor_class) {
            const ac = data.armor_class.base + (data.armor_class.dex_bonus ? ' + Dex' : '');
            appendField('Armor Class', ac);
        }
        if (Array.isArray(data.properties) && data.properties.length)
            appendField('Properties', data.properties.map(p => p.name).join(', '));
        appendParagraphs(descArray(data));
    }

    function renderEquipmentCategory(data) {
        if (Array.isArray(data.equipment) && data.equipment.length) {
            const h = document.createElement('h3');
            h.textContent = `Equipment (${data.equipment.length})`;
            resultEl.appendChild(h);
            const ul = document.createElement('ul');
            data.equipment.forEach(e => {
                const li = document.createElement('li');
                li.textContent = e.name;
                ul.appendChild(li);
            });
            resultEl.appendChild(ul);
        }
    }

    function renderLanguage(data) {
        if (data.type) appendField('Type', data.type);
        if (data.script) appendField('Script', data.script);
        if (Array.isArray(data.typical_speakers) && data.typical_speakers.length)
            appendField('Typical Speakers', data.typical_speakers.join(', '));
        appendParagraphs(data.desc ? [data.desc] : []);
    }

    function renderSkill(data) {
        if (data.ability_score?.name) appendField('Ability Score', data.ability_score.name);
        appendParagraphs(descArray(data));
    }

    function renderTrait(data) {
        if (Array.isArray(data.races) && data.races.length)
            appendField('Races', data.races.map(r => r.name).join(', '));
        if (Array.isArray(data.subraces) && data.subraces.length)
            appendField('Subraces', data.subraces.map(s => s.name).join(', '));
        if (Array.isArray(data.proficiencies) && data.proficiencies.length)
            appendField('Proficiencies', data.proficiencies.map(p => p.name).join(', '));
        appendParagraphs(descArray(data));
    }

    function renderProficiency(data) {
        if (data.type) appendField('Type', data.type);
        if (data.reference?.name) appendField('Reference', data.reference.name);
        if (Array.isArray(data.classes) && data.classes.length)
            appendField('Classes', data.classes.map(c => c.name).join(', '));
        if (Array.isArray(data.races) && data.races.length)
            appendField('Races', data.races.map(r => r.name).join(', '));
    }

    function renderSubclass(data) {
        if (data.class?.name) appendField('Parent Class', data.class.name);
        if (data.subclass_flavor) appendField('Flavor', data.subclass_flavor);
        appendParagraphs(descArray(data));
    }

    function renderAbilityScore(data) {
        if (data.full_name) appendField('Full Name', data.full_name);
        if (Array.isArray(data.skills) && data.skills.length)
            appendField('Skills', data.skills.map(s => s.name).join(', '));
        appendParagraphs(descArray(data));
    }

    const TYPE_RENDERERS = {
        'spells': renderSpell,
        'monsters': renderMonster,
        'classes': renderClass,
        'subclasses': renderSubclass,
        'races': renderRace,
        'subraces': renderSubrace,
        'equipment': renderEquipment,
        'equipment-categories': renderEquipmentCategory,
        'languages': renderLanguage,
        'skills': renderSkill,
        'traits': renderTrait,
        'proficiencies': renderProficiency,
        'ability-scores': renderAbilityScore,
        'conditions': renderGeneric,
        'rule-sections': renderGeneric,
        'features': renderGeneric,
        'magic-schools': renderGeneric,
        'damage-types': renderGeneric,
        'weapon-properties': renderGeneric
    };

    async function fetchExact(type, query) {
        const r = await fetch(`${API_BASE}/${type}/${encodeURIComponent(slug(query))}`);
        if (!r.ok) return null;
        const data = await r.json();
        if (!data || !data.name) return null;
        return data;
    }

    async function fetchSuggestions(type, query) {
        const r = await fetch(`${API_BASE}/${type}?name=${encodeURIComponent(query)}`);
        if (!r.ok) return [];
        const data = await r.json();
        return Array.isArray(data.results) ? data.results : [];
    }

    function showSuggestions(type, suggestions) {
        suggestionsEl.replaceChildren();
        const label = TYPE_LABELS[type] || type;
        const heading = document.createElement('li');
        heading.className = 'lookup-suggestions-heading muted';
        heading.textContent = `No exact match. Did you mean:`;
        suggestionsEl.appendChild(heading);

        suggestions.forEach(s => {
            const li = document.createElement('li');
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'lookup-suggestion';
            btn.textContent = s.name;
            btn.addEventListener('click', async () => {
                clear();
                setStatus(`Loading ${label} "${s.name}"…`);
                const exact = await fetchExact(type, s.index || s.name);
                if (exact) {
                    setStatus('');
                    renderResult(type, exact);
                } else {
                    setStatus(`Couldn't load "${s.name}".`);
                }
            });
            li.appendChild(btn);
            suggestionsEl.appendChild(li);
        });
        suggestionsEl.hidden = false;
    }

    queryInput.addEventListener('input', scheduleAutocomplete);
    queryInput.addEventListener('focus', () => {
        if (queryInput.value.trim().length === 0) scheduleAutocomplete();
    });
    queryInput.addEventListener('blur', () => {
        // Delay so click on suggestion fires first
        setTimeout(hideAutocomplete, 150);
    });
    queryInput.addEventListener('keydown', (e) => {
        if (autocompleteEl.hidden) return;
        if (e.key === 'ArrowDown') {
            e.preventDefault();
            setActiveSuggestion(Math.min(activeSuggestionIndex + 1, currentSuggestions.length - 1));
        } else if (e.key === 'ArrowUp') {
            e.preventDefault();
            setActiveSuggestion(Math.max(activeSuggestionIndex - 1, -1));
        } else if (e.key === 'Enter' && activeSuggestionIndex >= 0) {
            e.preventDefault();
            pickSuggestion(currentSuggestions[activeSuggestionIndex]);
        } else if (e.key === 'Escape') {
            hideAutocomplete();
        }
    });
    typeSelect.addEventListener('change', () => {
        hideAutocomplete();
        if (queryInput.value.trim()) scheduleAutocomplete();
    });

    form.addEventListener('submit', async (e) => {
        e.preventDefault();
        hideAutocomplete();
        clear();
        const data = new FormData(form);
        const type = data.get('type');
        const query = (data.get('query') || '').toString().trim();
        if (!query) return;

        const label = TYPE_LABELS[type] || type;
        setStatus(`Searching for ${label} "${query}"…`);

        try {
            const exact = await fetchExact(type, query);
            if (exact) {
                setStatus('');
                renderResult(type, exact);
                return;
            }
            const suggestions = await fetchSuggestions(type, query);
            if (suggestions.length) {
                setStatus('');
                showSuggestions(type, suggestions);
            } else {
                setStatus(`Sorry, nothing was returned for ${label} "${query}".`);
            }
        } catch (err) {
            setStatus('Something went wrong reaching the D&D API. Please try again.');
        }
    });
})();
