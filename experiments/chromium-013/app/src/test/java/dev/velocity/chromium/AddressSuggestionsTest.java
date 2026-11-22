package dev.velocity.chromium;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public final class AddressSuggestionsTest {
    @Test public void prioritizesOpenExactMatchAndSuppressesPrivateResults() {
        var input = List.of(
            new AddressSuggestions.Item("https://waseda.jp/", "早稲田", null, "ブックマーク"),
            new AddressSuggestions.Item("https://waseda.jp/", "早稲田", "tab", "開いているタブ"),
            new AddressSuggestions.Item("https://example.org/早稲田", "大学案内", null, "履歴"),
            new AddressSuggestions.Item("javascript:早稲田", "早稲田", null, "履歴"));
        var text = AddressSuggestions.choose("早稲田", input, false);
        assertEquals(2, text.size());
        assertEquals("tab", text.get(0).openTabId());
        assertEquals("tab", AddressSuggestions.choose("ＷＡＳＥＤＡ", input, false)
            .get(0).openTabId());
        assertTrue(AddressSuggestions.choose("早稲田", input, true).isEmpty());
        assertTrue(AddressSuggestions.choose("早", input, false).isEmpty());
    }
}
