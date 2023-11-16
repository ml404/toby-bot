package toby.helpers;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.entities.emoji.UnicodeEmoji;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.util.List;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

public class DnDHelper {

    private static final AtomicInteger initiativeIndex = new AtomicInteger(0);

    private static final Map<Long, Boolean> hasEmbedForGuildMap = new HashMap<>();

    private static LinkedList<Map.Entry<String, Integer>> sortedEntries = new LinkedList<>();

    public static void rollInitiativeForMembers(List<Member> memberList, Member dm, Map<String, Integer> initiativeMap) {
        List<Member> nonDmMembers = memberList.stream().filter(memberInChannel -> memberInChannel != dm && !memberInChannel.getUser().isBot()).toList();
        if (nonDmMembers.isEmpty()) return;
        nonDmMembers.forEach(target -> rollAndAddToMap(initiativeMap, target.getUser().getEffectiveName(), 0));

        sortedEntries = new LinkedList<>(initiativeMap.entrySet());
        // Sort the list based on values
        sortedEntries.sort(Map.Entry.comparingByValue());
    }

    public static void rollInitiativeForString(List<String> nameList, Map<String, Integer> initiativeMap) {
        if (nameList.isEmpty()) return;
        nameList.forEach(name -> rollAndAddToMap(initiativeMap, name, 0));
        sortedEntries = new LinkedList<>(initiativeMap.entrySet());
        // Sort the list based on values
        sortedEntries.sort(Map.Entry.comparingByValue(Comparator.reverseOrder()));
    }

    private static void rollAndAddToMap(Map<String, Integer> initiativeMap, String name, int modifier) {
        int diceRoll = DnDHelper.rollDiceWithModifier(20, 1, modifier);
        initiativeMap.put(name, diceRoll);
    }

    @NotNull
    public static StringBuilder createTurnOrderString() {
        StringBuilder description = new StringBuilder();
        for (int i = 0; i < sortedEntries.size(); i++) {
            int currentIndex = (initiativeIndex.get() + i) % sortedEntries.size();
            description.append(sortedEntries.get(currentIndex).getKey()).append(": ")
                    .append(sortedEntries.get(currentIndex).getValue()).append("\n");
        }
        return description;
    }

    public static int rollDiceWithModifier(int diceValue, int diceToRoll, int modifier) {
        return rollDice(diceValue, diceToRoll) + modifier;
    }

    public static int rollDice(int diceValue, int diceToRoll) {
        int rollTotal = 0;
        Random rand = ThreadLocalRandom.current();
        for (int i = 0; i < diceToRoll; i++) {
            int roll = rand.nextInt(diceValue) + 1; //This results in 1 - 20 (instead of 0 - 19) for default value
            rollTotal += roll;
        }
        return rollTotal;
    }


    public static void incrementTurnTable(InteractionHook hook, long guildId) {
        incrementIndex();
        EmbedBuilder embedBuilder = getInitiativeEmbedBuilder();
        sendOrEditInitiativeMessage(guildId, hook, embedBuilder);
    }

    private static void incrementIndex() {
        getInitiativeIndex().incrementAndGet();
        if (initiativeIndex.get() >= DnDHelper.getSortedEntries().size()) {
            initiativeIndex.set(0);
        }
    }

    public static void decrementTurnTable(InteractionHook hook, long guildId) {
        decrementIndex();
        EmbedBuilder embedBuilder = getInitiativeEmbedBuilder();
        sendOrEditInitiativeMessage(guildId, hook, embedBuilder);
    }

    private static void decrementIndex() {
        initiativeIndex.decrementAndGet();
        if (initiativeIndex.get() < 0) {
            initiativeIndex.set(getSortedEntries().size() - 1);
        }
    }

    @NotNull
    public static TableButtons getInitButtons() {
        UnicodeEmoji prevEmoji = Emoji.fromUnicode("⬅️");
        UnicodeEmoji xEmoji = Emoji.fromUnicode("❌");
        UnicodeEmoji nextEmoji = Emoji.fromUnicode("➡️");
        Button prev = Button.primary("init:prev", prevEmoji);
        Button clear = Button.primary("init:clear", xEmoji);
        Button next = Button.primary("init:next", nextEmoji);
        return new TableButtons(prev, clear, next);
    }

    public static void sendOrEditInitiativeMessage(long guildId, InteractionHook hook, EmbedBuilder embedBuilder) {
        boolean hasCurrentEmbed = hasCurrentEmbed(guildId);
        TableButtons initButtons = getInitButtons();
        hook.deleteOriginal().queue();
        if (!hasCurrentEmbed) {
                hook
                    .sendMessageEmbeds(embedBuilder.build())
                    .setActionRow(initButtons.prev(), initButtons.clear(), initButtons.next())
                    .queue();
            hasEmbedForGuildMap.put(guildId, true);
        } else {
                hook
                     .editOriginalEmbeds(embedBuilder.build())
                     .setActionRow(initButtons.prev(), initButtons.clear(), initButtons.next())
                     .queue();
        }
    }

    @NotNull
    public static EmbedBuilder getInitiativeEmbedBuilder() {
        EmbedBuilder embedBuilder = new EmbedBuilder();
        embedBuilder.setColor(Color.GREEN);
        embedBuilder.setTitle("Initiative Order");
        StringBuilder description = createTurnOrderString();
        embedBuilder.setDescription(description.toString());
        return embedBuilder;
    }

    public static void clearInitiative(long guildId) {
        hasEmbedForGuildMap.remove(guildId);
        initiativeIndex.setPlain(0);
        sortedEntries.clear();
    }

    public record TableButtons(Button prev, Button clear, Button next) {
    }

    public static LinkedList<Map.Entry<String, Integer>> getSortedEntries() {
        return sortedEntries;
    }

    public static boolean hasCurrentEmbed(long guildId) {
        return hasEmbedForGuildMap.getOrDefault(guildId, false);
    }

    public static void setHasEmbedForGuild(long guildId, boolean hasMessage) {
            hasEmbedForGuildMap.put(guildId, hasMessage);
    }

    public static AtomicInteger getInitiativeIndex() {
        return initiativeIndex;
    }
}
