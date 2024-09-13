-- Insert data into the brothers table
INSERT INTO public.brothers (discord_id, brother_name) VALUES
    (1, 'John'),
    (2, 'Mike'),
    (3, 'Sarah');

-- Insert data into the config table
INSERT INTO public.config (name, "value", guild_id) VALUES
    ('Setting1', 'Value1', 'guild1'),
    ('Setting2', 'Value2', 'guild2'),
    ('Setting3', 'Value3', 'all');

-- Insert data into the excuse table
INSERT INTO public.excuse (guild_id, author, excuse, approved) VALUES
    (1, 'Author1', 'Excuse1', true),
    (2, 'Author2', 'Excuse2', true),
    (1, 'Author3', 'Excuse3', false);

-- Insert data into the music_files table
INSERT INTO public.music_files (file_name, music_blob, id, file_vol, guild_id, discord_id, index) VALUES
    ('file1', 'base64encodeddata1', '101_1_1', 5, 101, 1, 1),
    ('file2', 'base64encodeddata2', '102_1_1', 3, 102, 1, 1),
    ('file3', 'base64encodeddata3', '103_1_1', 3, 103, 1, 1);

-- Insert data into the user table
INSERT INTO public."user" (discord_id, guild_id, super_user, music_permission, dig_permission ,meme_permission, social_credit) VALUES
    (101, 1, true, true, false, true, 100),
    (102, 1, false, true, true, true, 50),
    (103, 1, false, false, false, true, 75);
