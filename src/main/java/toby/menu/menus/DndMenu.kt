package toby.menu.menus;

import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import toby.helpers.HttpHelper;
import toby.menu.IMenu;
import toby.menu.MenuContext;

import java.io.UnsupportedEncodingException;

import static toby.command.commands.dnd.DnDCommand.*;

public class DndMenu implements IMenu {
    @Override
    public void handle(MenuContext ctx, Integer deleteDelay) {
        StringSelectInteractionEvent event = ctx.getSelectEvent();
        event.deferReply().queue();
        try {
            determineDnDRequestType(event, deleteDelay, getType(event));
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private void determineDnDRequestType(StringSelectInteractionEvent event, Integer deleteDelay, String type) throws UnsupportedEncodingException {
        switch (type) {
            case SPELL_NAME -> sendDndApiRequest(event, SPELL_NAME, "spells", deleteDelay);
            case CONDITION_NAME-> sendDndApiRequest(event, CONDITION_NAME, "conditions", deleteDelay);
            case RULE_NAME-> sendDndApiRequest(event, RULE_NAME, "rule-sections", deleteDelay);
            case FEATURE_NAME -> sendDndApiRequest(event, FEATURE_NAME, "features", deleteDelay);
        }
    }

    private void sendDndApiRequest(StringSelectInteractionEvent event, String typeName, String typeValue, Integer deleteDelay) {
        String query = event.getValues().get(0); // Get the selected option
        event.getMessage().delete().queue();
        doLookUpAndReply(event.getHook(), typeName, typeValue, query, new HttpHelper(), deleteDelay);
    }

    private static String getType(StringSelectInteractionEvent event) {
        return event.getComponentId().split(":")[1];
    }

    public String getName() {
        return "dnd";
    }

}
