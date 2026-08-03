**Q**: How do I change the intro song?  
**A**: Use `/setintro link` with a YouTube URL, or `/setintro attachment` with an MP3. Both take an optional `volume`.

**Q**: What if I forget to set a volume?  
**A**: The bot will default to 90% volume if none is specified.

**Q**: How do I see what I've already got set?  
**A**: `/listintros` shows every slot with its volume, clip range and source, plus a link through to the web dashboard.

**Q**: The video I want is longer than 15 seconds — can I still use it?  
**A**: Yes. Pass `start` and `end` to `/setintro` (e.g. `start:1:04 end:1:16`) and the bot plays just that stretch.
Both accept `mm:ss` or a raw number of seconds. The clip itself still has to fit inside the 15-second limit.

**Q**: Can I rename an intro or trim it after the fact?  
**A**: `/editintro`, then pick the intro — a form opens with its name, volume and clip bounds pre-filled.
Clearing a clip field removes that bound, so blanking both restores the full track.

**Q**: I deleted the wrong intro.  
**A**: The `/deleteintro` confirmation has an **Undo** button, good for 10 minutes.

**Q**: Where's the web version?  
**A**: <https://www.toby-bot.co.uk/intro/guilds> — same intros, with drag-to-reorder and a draggable trim bar for picking clips.
