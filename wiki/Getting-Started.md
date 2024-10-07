#### Prerequisites:
- **Java 17+**
- **Kotlin**
- **JDA 5.x.x**
- **PostgreSQL**

#### Installation:
1. **Invite the Bot**: [Link] to invite the bot to your server.
2. **Clone the Repository**:
   ```bash
   git clone https://github.com/ml404/toby-bot.git
   cd repository
   ```
3. **Configuration**:
   Set up your `application.properties`:
   ```properties
   token=YOUR_BOT_TOKEN
   database_url=jdbc:postgresql://localhost:5432/yourdb
   logging.level.org.springframework=INFO
   ```
4. **Run the Bot**:
   ```bash
   ./gradlew bootRun
   ```

You should now see the bot running and active on your server!
