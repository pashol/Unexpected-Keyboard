# Tapped Suggestion Backspace Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Backspace delete one character after a tapped suggestion while preserving full-word undo for space-bar autocomplete.

**Architecture:** Keep candidate replacement and Backspace behavior in `KeyEventHandler`. Split the existing suggestion acceptance flow into a normal, tapped-candidate path and an explicit undoable space-bar path; only the latter records the replaced word and undo action. Extend the existing fake `InputConnection` just enough to model Backspace so the test proves visible editor output.

**Tech Stack:** Java, Android `InputConnection`, JUnit 4, Gradle Android unit tests.

---

### Task 1: Lock the Two Acceptance Behaviors With Tests

**Files:**
- Modify: `test/KeyEventHandlerTest.java:3-11, 85-95, 300-350`

- [ ] **Step 1: Add a failing tapped-suggestion Backspace test and fake-editor Backspace support**

  Add the `KeyEvent` import and the following test after `entering_suggestion_does_not_append_a_space`:

  ```java
  @Test
  public void backspace_after_tapped_suggestion_deletes_one_character()
  {
    FakeInputConnection connection = new FakeInputConnection("Informa");
    KeyEventHandler handler = new_handler(connection);
    handler._typedword._enabled = true;
    handler._typedword.set_current_word("Informa");

    handler.suggestion_entered("Informatik");
    handler._last_action = KeyEventHandler.LastAction.SUGGESTION_ENTERED;
    handler.handle_backspace();

    assertEquals("Informati", connection.text());
  }
  ```

  In `FakeInputConnection.invoke`, handle the `sendKeyEvent` call used by `KeyEventHandler.send_key_down_up`. On an `ACTION_UP` `KEYCODE_DEL` event, delete the character immediately before `cursor` and decrement `cursor`; leave all other events unchanged:

  ```java
  if (method.getName().equals("sendKeyEvent"))
  {
    KeyEvent event = (KeyEvent)args[0];
    if (event.getAction() == KeyEvent.ACTION_UP
        && event.getKeyCode() == KeyEvent.KEYCODE_DEL && cursor > 0)
    {
      text.deleteCharAt(cursor - 1);
      cursor--;
    }
  }
  ```

- [ ] **Step 2: Run the focused test to verify it fails**

  Run:

  ```sh
  JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/pascal/Android/Sdk ./gradlew testDebugUnitTest --tests juloo.keyboard2.KeyEventHandlerTest.backspace_after_tapped_suggestion_deletes_one_character
  ```

  Expected: FAIL because `suggestion_entered` still records the tapped replacement as undoable and restores `Informa`.

- [ ] **Step 3: Add a failing space-bar completion undo regression test**

  Add this test next to the tapped-candidate test:

  ```java
  @Test
  public void backspace_after_space_bar_completion_restores_typed_word()
  {
    FakeInputConnection connection = new FakeInputConnection("Informa");
    KeyEventHandler handler = new_handler(connection);
    handler._typedword._enabled = true;
    handler._typedword.set_current_word("Informa");
    handler._space_bar_auto_complete = true;
    handler._suggestions.suggestions[0] = "Informatik";
    handler._suggestions.count = 1;

    handler.handle_space_bar();
    handler._last_action = KeyEventHandler.LastAction.SUGGESTION_ENTERED;
    handler.handle_backspace();

    assertEquals("Informa", connection.text());
  }
  ```

- [ ] **Step 4: Run both focused tests to verify the automatic-completion regression test passes and the tapped-candidate test still fails**

  Run:

  ```sh
  JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/pascal/Android/Sdk ./gradlew testDebugUnitTest --tests juloo.keyboard2.KeyEventHandlerTest.backspace_after_tapped_suggestion_deletes_one_character --tests juloo.keyboard2.KeyEventHandlerTest.backspace_after_space_bar_completion_restores_typed_word
  ```

  Expected: one failure for the tapped candidate and one pass for space-bar completion.

- [ ] **Step 5: Commit the failing tests**

  ```sh
  git add test/KeyEventHandlerTest.java
  git commit -m "test: cover suggestion backspace behavior"
  ```

### Task 2: Restrict Full-Word Undo to Space-Bar Completion

**Files:**
- Modify: `srcs/juloo.keyboard2/KeyEventHandler.java:157-165, 609-625`
- Test: `test/KeyEventHandlerTest.java:85-130, 300-360`

- [ ] **Step 1: Add an explicit private undoable acceptance helper**

  Leave the `Config.IKeyEventHandler` override as the normal tapped-candidate path. It must only replace the current word:

  ```java
  @Override
  public void suggestion_entered(String text)
  {
    replace_suggestion(text, false);
  }
  ```

  Add this helper immediately below it:

  ```java
  private void replace_suggestion(String text, boolean undoable)
  {
    String old = _typedword.get();
    int cur_rel = _typedword.cursor_relative();
    replace_surrounding_text(old.length() + cur_rel, -cur_rel, text);
    if (undoable)
    {
      last_replaced_word = old;
      last_replacement_word_len = text.length();
      _next_last_action = LastAction.SUGGESTION_ENTERED;
    }
  }
  ```

  This preserves the original replacement range, including cursor-in-word behavior, but prevents a tapped candidate from arming `handle_backspace`'s full-word restore.

- [ ] **Step 2: Route only the space-bar autocomplete branch through the undoable helper**

  In `handle_space_bar`, replace:

  ```java
  suggestion_entered(_suggestions.suggestions[0] + " ");
  ```

  with:

  ```java
  replace_suggestion(_suggestions.suggestions[0] + " ", true);
  ```

- [ ] **Step 3: Run the two regression tests to verify they pass**

  Run:

  ```sh
  JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/pascal/Android/Sdk ./gradlew testDebugUnitTest --tests juloo.keyboard2.KeyEventHandlerTest.backspace_after_tapped_suggestion_deletes_one_character --tests juloo.keyboard2.KeyEventHandlerTest.backspace_after_space_bar_completion_restores_typed_word
  ```

  Expected: BUILD SUCCESSFUL; both tests pass.

- [ ] **Step 4: Run the complete handler test class**

  Run:

  ```sh
  JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/pascal/Android/Sdk ./gradlew testDebugUnitTest --tests juloo.keyboard2.KeyEventHandlerTest
  ```

  Expected: BUILD SUCCESSFUL; all `KeyEventHandlerTest` tests pass, including the no-trailing-space candidate test and existing autocomplete undo/learning coverage.

- [ ] **Step 5: Commit the production change**

  ```sh
  git add srcs/juloo.keyboard2/KeyEventHandler.java test/KeyEventHandlerTest.java
  git commit -m "fix: preserve tapped suggestion edits"
  ```
