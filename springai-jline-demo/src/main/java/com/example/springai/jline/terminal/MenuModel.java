package com.example.springai.jline.terminal;

import java.util.List;

/**
 * 纯逻辑：菜单选中索引管理，上下移动循环绕回。无任何 IO，可单测。
 */
public final class MenuModel {

    private final List<String> items;
    private int selected;

    public MenuModel(List<String> items) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items 不能为空");
        }
        this.items = List.copyOf(items);
        this.selected = 0;
    }

    public List<String> items() {
        return items;
    }

    public int selectedIndex() {
        return selected;
    }

    public String selectedLabel() {
        return items.get(selected);
    }

    public int size() {
        return items.size();
    }

    public void down() {
        selected = (selected + 1) % items.size();
    }

    public void up() {
        selected = (selected - 1 + items.size()) % items.size();
    }
}
