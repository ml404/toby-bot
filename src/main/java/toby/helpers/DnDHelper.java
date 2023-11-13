package toby.helpers;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

public class DnDHelper {

    private static final AtomicInteger initiativeIndex = new AtomicInteger(0);

    private static final Map<Long, Message> currentMessageForGuild = new HashMap<>();

    private static LinkedList<Map.Entry<Member, Integer>> sortedEntries;

    public static List<Map.Entry<Member, Integer>> rollInitiativeForMembers(List<Member> memberList, Member dm, Map<Member, Integer> initiativeMap) {
        sortedEntries = new LinkedList<>(initiativeMap.entrySet());
        memberList.stream().filter(memberInChannel -> memberInChannel != dm).forEach(target -> {
            int diceRoll = DnDHelper.rollDiceWithModifier(20, 1, 0);
            initiativeMap.put(target, diceRoll);
        });

        // Sort the list based on values
        sortedEntries.sort(Map.Entry.comparingByValue());

        return sortedEntries;
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

    public static LinkedList<Map.Entry<Member, Integer>> getSortedEntries() {
        return sortedEntries;
    }

    public static Message getCurrentMessage(long guildId) {
        return currentMessageForGuild.get(guildId);
    }

    public static void setCurrentMessage(long guildId, Message message) {
        currentMessageForGuild.put(guildId, message);
    }

    public static AtomicInteger getInitiativeIndex(){
        return initiativeIndex;
    }
}
