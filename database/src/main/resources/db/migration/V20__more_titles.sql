-- Extends the vanity-title catalog with cheap-entry, mid-band, and
-- prestige tiers. The new top tier (1,000,000 credits) exists to give
-- users sitting on inflated balances from a previously-bugged minigame
-- a visible drain back into the shop.

INSERT INTO title (label, cost, description, color_hex, hoisted) VALUES
    ('🐣 Hatchling',          50,      'Just landed on the server.',                     '#95A5A6', FALSE),
    ('☕ Caffeinated',         350,     'Knows the morning queue by name.',               '#6E4B3A', FALSE),
    ('🎲 High Roller',        1200,    'Lives for the wager.',                           '#E74C3C', FALSE),
    ('📈 Day Trader',         1750,    'Watches every TOBY tick.',                       '#16A085', FALSE),
    ('💎 Diamond Hands',      3500,    'Wouldn''t sell, wouldn''t fold.',                '#B9F2FF', TRUE),
    ('🏆 Champion',           25000,   'Top of the leaderboard.',                        '#D4AF37', TRUE),
    ('🌟 Luminary',           100000,  'Outshines the rest.',                            '#F39C12', TRUE),
    ('🐉 Dragon',             250000,  'Hoards the credits.',                            '#C0392B', TRUE),
    ('🦅 Sovereign Eagle',    500000,  'Soars far above the citizens.',                  '#4A6FA5', TRUE),
    ('🪐 Cosmic Comrade',     1000000, 'A balance beyond the stratosphere — drained.',   '#8E44AD', TRUE);
