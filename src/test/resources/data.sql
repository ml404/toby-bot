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
INSERT INTO public.excuse (guild_id, author, excuse, approved, id) VALUES
    (1, 'Author1', 'Excuse1', true, 1),
    (2, 'Author2', 'Excuse2', true, 2),
    (1, 'Author3', 'Excuse3', false, 3);

-- Insert data into the music_files table
INSERT INTO public.music_files (file_name, music_blob, id, file_vol) VALUES
    ('Song1.mp3', 'base64encodeddata1', 'file1', 5),
    ('Song2.mp3', 'base64encodeddata2', 'file2', 3);

-- Insert data into the user table
INSERT INTO public."user" (discord_id, guild_id, super_user, music_permission, dig_permission ,meme_permission, music_file_id, social_credit) VALUES
    (101, 1, true, true, false, true, 'file1', 100),
    (102, 1, false, true, true, true, 'file2', 50),
    (103, 2, false, false, false, true, 'file3', 75);
