package jp.ddo.pigsty.HabitBrowser.Features.Browser.Model;

public class ActionInfo {
    private int actionId;
    private String actionName;

    public ActionInfo() {
        this.actionName = null;
        this.actionId = 0;
    }

    public int getActionId() { return actionId; }
    public String getActionName() { return actionName; }
    public void setActionId(int actionId) { this.actionId = actionId; }
    public void setActionName(String actionName) { this.actionName = actionName; }
}
