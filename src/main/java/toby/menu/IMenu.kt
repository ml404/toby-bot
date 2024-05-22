package toby.menu;

public interface IMenu {

    void handle(MenuContext ctx, Integer deleteDelay);

    String getName();

}
