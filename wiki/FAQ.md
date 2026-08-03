**Q**: How do I change the intro song?  
**A**: Use `/setintro link` with a YouTube URL, or `/setintro attachment` with an MP3. Both take an optional `volume`.

**Q**: Someone just posted a great track in chat — can I grab it without re-uploading?  
**A**: Right-click (or long-press) the message → **Apps → Set as my intro**. It takes the MP3 attachment if
there is one, otherwise the first link in the message. It always sets *your* intro, never the poster's.

**Q**: Can I keep an intro but stop it playing?  
**A**: Yes — switch it off. On the web there's a toggle on each row; on Discord, `/editintro` has a
**Play on join?** field. A switched-off intro keeps its slot, name, volume and clip, and is skipped when
TobyBot picks one. Switch every intro off and nothing plays at all.

**Q**: What if I forget to set a volume?  
**A**: The bot will default to 90% volume if none is specified.

**Q**: How do I see what I've already got set?  
**A**: `/listintros` shows every slot with its volume, clip range and source, plus a link through to the web dashboard.

**Q**: The video I want is longer than 15 seconds — can I still use it?  
**A**: Yes. Pass `start` and `end` to `/setintro` (e.g. `start:1:04 end:1:16`) and the bot plays just that stretch.
Both accept `mm:ss` or a raw number of seconds. The clip itself still has to fit inside the 15-second limit.

**Q**: I'm an admin — can I fix someone else's intro?  
**A**: Yes. `/setintro`, `/listintros`, `/editintro` and `/deleteintro` all take a user option for super-users.

**Q**: Can I rename an intro or trim it after the fact?  
**A**: `/editintro`, then pick the intro — a form opens with its name, volume and clip bounds pre-filled.
Clearing a clip field removes that bound, so blanking both restores the full track.

**Q**: I deleted the wrong intro.  
**A**: The `/deleteintro` confirmation has an **Undo** button, good for 10 minutes.

**Q**: Why didn't my intro play when I rejoined?  
**A**: An intro won't replay within 60 seconds of the last one, so channel-hopping and reconnects don't
retrigger it. Wait a minute, or play it on demand with `/play intro`.

**Q**: With several intros set, why do I keep hearing the same one?  
**A**: You shouldn't any more — the pick now skips whichever intro played last, so consecutive joins always differ.

**Q**: TobyBot keeps DMing me to set an intro.  
**A**: It'll only nudge you once a day per server now. To silence it entirely, run `/notify set INTRO_PROMPT off`.

**Q**: Where's the web version?  
**A**: <https://www.toby-bot.co.uk/intro/guilds> — same intros, with drag-to-reorder and a draggable trim bar for picking clips.
