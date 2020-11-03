package toby;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import toby.handler.Handler;

import javax.security.auth.login.LoginException;

public class BotMain extends ListenerAdapter {

    //    private boolean shuttingDown = false;
    public static JDA jda;

    public static void main(String[] args) throws LoginException {
        JDABuilder builder = JDABuilder.createDefault(BotConfig.get("token"));
        builder.addEventListeners(new Handler());
        jda = builder.build();
    }
//            public void shutdown () {
//            if (shuttingDown)
//                return;
//            shuttingDown = true;
//            if (jda.getStatus() != JDA.Status.SHUTTING_DOWN) {
//                jda.getGuilds().forEach(g ->
//                        jda.shutdown());
//
//                System.exit(0);
//            }
//        }
}

