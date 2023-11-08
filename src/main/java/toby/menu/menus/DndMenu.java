package toby.menu.menus;

import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import toby.helpers.HttpHelper;
import toby.menu.IMenu;
import toby.menu.MenuContext;

import java.io.UnsupportedEncodingException;

import static toby.command.commands.fetch.DnDCommand.*;

public class DndMenu implements IMenu {
    @Override
    public void handle(MenuContext ctx, Integer deleteDelay) {
        StringSelectInteractionEvent event = ctx.getStringSelectInteractionEvent();
        event.deferReply().queue();
        try {
            determineDnDRequestType(event, deleteDelay);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private void determineDnDRequestType(StringSelectInteractionEvent event, Integer deleteDelay) throws UnsupportedEncodingException {
        String type = event.getComponentId().split(":")[1];

        switch (type) {
            case "spell" -> sendDndApiRequest(event, SPELL_NAME, "spells", deleteDelay);
            case "condition" -> sendDndApiRequest(event, CONDITION_NAME, "conditions", deleteDelay);
            case "rule" -> sendDndApiRequest(event, RULE_NAME, "rule-sections", deleteDelay);
            case "feature" -> sendDndApiRequest(event, FEATURE_NAME, "features", deleteDelay);
        }
    }

    private void sendDndApiRequest(StringSelectInteractionEvent event, String typeName, String typeValue, Integer deleteDelay) {
        String query = event.getValues().get(0); // Get the selected option
        event.getMessage().delete().queue();
        doLookUpAndReply(event.getHook(), typeName, typeValue, query, new HttpHelper(), deleteDelay);
    }

    public String getName() {
        return "dnd";
    }

}
