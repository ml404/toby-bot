---

### **Home**

---

#### Welcome to the Toby-Bot Wiki!

**Toby-Bot** is a feature-rich Discord bot designed to enhance user interactions with intro songs and custom music features. Whether you're managing user-uploaded files, handling music URLs, or setting personalized intro songs, this bot is built to make the process simple and fun.

---

### Features:
- **Plays Music**: Comprehensive music player that allows for pausing, queuing, skipping, changing volume, displaying the currently playing song, looping the song and with the correct permissions having unskippable and unstoppable songs!
- **Intro Songs**: Handle YouTube URLs, file uploads, and custom volume levels to play when you join a voice chat.
- **DnD lookups**: Lookup spells, rules, conditions, and features using the DnD
- **Server moderation**: Use a variety of moderation tools for your server, including setting server config for who can interact with music commands, who can use the bot to post memes and other similar things
- **Event Handling**: Uses an EventWaiter pattern to efficiently manage user inputs and responses.
- **Custom Logging**: Integrated logging with contextual information (Guild, User) for improved debugging.
- **Commands**: Intuitive command interface to manage your settings, intro songs, and more.

[Start Here](Getting-Started) to get the bot up and running on your server!

---

### **Features**

---

#### **Intro Song Management**

- **URL Support**: Provide a YouTube URL or another music link.
- **File Upload**: Upload a music file directly into the Discord chat.
- **Volume Control**: Optionally set a volume between 0 and 100 when configuring your intro.

#### **EventWaiter Pattern**

The bot leverages an EventWaiter pattern to manage user inputs and responses seamlessly. This ensures that responses are timed correctly and users are properly prompted when necessary.

For example, when setting an intro song, the bot will wait for the user's response (URL or file upload) and store the information accordingly.

#### **Custom Logging**

The bot includes a logging system that adds contextual information (like Guild or User) for each log line. This helps during debugging, giving insight into where and when each event occurs.

Logging is managed through `DiscordLogger`, injecting useful details such as:
- **Guild name and ID**
- **User who triggered the event**

---

### **Technical Details**

---

#### **Bot Architecture**
- **Kotlin-based**: The bot is built using Kotlin with the JDA library to interact with the Discord API.
- **Spring Boot Integration**: This bot uses Spring Boot for dependency injection and bean management.
- **PostgreSQL**: All user data, including `MusicDto`, is persisted in a PostgreSQL database.

#### **Database**

The bot stores user intro songs using a `MusicDto` table, where each row represents a user's intro song, associated with the user and guild. Ensure you have PostgreSQL running before starting the bot.

- `id`: Primary key
- `userId`: Discord User ID
- `guildId`: Discord Guild ID
- `fileName`: The file name of the uploaded file or the URL string
- `volume`: Volume level of the intro

#### **Testing**

Unit testing is done using `MockK` for mocking interactions. You can run tests using the following:

```bash
./gradlew test
```

---

### **Contribution Guide**

---

#### How to Contribute:
We welcome contributions to improve the bot! Here’s how to get started:

1. **Fork the repository**.
2. **Create a branch** for your feature:
   ```bash
   git checkout -b feature/your-feature-name
   ```
3. **Make your changes** and ensure tests pass.
4. **Open a Pull Request**.

#### Coding Style:
- **Kotlin** conventions (Idiomatic Kotlin, concise null safety checks).
- Keep methods concise and well-documented.

#### Branching Strategy:
- **Main**: Production-ready code.
- **Develop**: Features under active development.
- **Feature branches**: For individual features or fixes.

[FAQ](FAQ)