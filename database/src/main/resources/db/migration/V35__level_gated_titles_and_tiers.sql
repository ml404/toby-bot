-- Level-gated titles. These form a ladder spanning levels 5 -> 200. Most users
-- will receive them for free via LevelUpListener.unlockTitles when crossing the
-- gate; the `cost` exists as a fallback for users who were already past the
-- level before the title was added.
--
-- `required_level` was added in V34 with default 0, so existing V13/V20 rows
-- remain ungated. New rows below specify it explicitly.

INSERT INTO title (label, cost, description, color_hex, hoisted, required_level) VALUES
    ('🌱 Sprout',           200,     'Found their footing.',                          '#A3D977', FALSE,   5),
    ('🔥 Kindled',          600,     'A spark that won''t go out.',                   '#E67E22', FALSE,  10),
    ('⚔️ Initiate',         1500,    'First blade drawn.',                            '#8E8E93', FALSE,  15),
    ('🛡️ Vanguard',         4000,    'Holds the line.',                               '#5D6D7E', TRUE,   25),
    ('🧭 Pathfinder',       8000,    'Maps the uncharted.',                           '#27AE60', FALSE,  35),
    ('🪙 Platinum Citizen', 15000,   'Long-haul regular.',                            '#E5E4E2', TRUE,   50),
    ('🦉 Sage',             30000,   'Speaks rarely; weighs everything.',             '#7D6608', FALSE,  65),
    ('⚡ Stormcaller',      60000,   'Summons thunder in the voice channels.',        '#1F6FEB', TRUE,   75),
    ('🌌 Voidwalker',       120000,  'Comfortable in the silence between stars.',     '#34237C', TRUE,   90),
    ('👑 Grandmaster',      250000,  'The bench other masters measure against.',      '#B7950B', TRUE,  100),
    ('🐲 Wyrmlord',         500000,  'Older than the channel logs.',                  '#922B21', TRUE,  125),
    ('🔮 Mythic',           1000000, 'Heard about, rarely seen.',                     '#7B2CBF', TRUE,  150),
    ('🌠 Ascendant',        2500000, 'Beyond the curve.',                             '#F4D03F', TRUE,  175),
    ('♾️ Eternal',          5000000, 'The leaderboard footnote that never moves.',    '#000000', TRUE,  200);
