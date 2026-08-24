package juloo.keyboard2;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.SurroundingText;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class CurrentlyTypedWordInstrumentationTest
{
  @Test
  public void api_31_surrounding_text_mid_word_preserves_cursor_without_context()
  {
    final List<List<String>> published = new ArrayList<>();
    CurrentlyTypedWord word = new CurrentlyTypedWord(null,
        new CurrentlyTypedWord.Callback()
        {
          public void currently_typed_word(String text, boolean sentenceStart) {}

          public void currently_typed_word(String text, boolean sentenceStart,
              List<String> words)
          {
            published.add(words);
          }
        });
    Config config = config();
    config.editor_config.initial_text_before_cursor = null;
    config.editor_config.initial_text_after_cursor = null;
    config.editor_config.initial_sel_start = 6;
    config.editor_config.initial_sel_end = 6;
    SurroundingText text = new SurroundingText("hello world", 6, 6, 0);

    word.started(config, input_connection(text), 31);

    assertEquals("world", word.get());
    assertEquals(-5, word.cursor_relative());
    assertEquals(Collections.emptyList(), published.get(0));
  }

  private Config config()
  {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    SharedPreferences prefs = context.getSharedPreferences(
        "currently-typed-word-test", Context.MODE_PRIVATE);
    prefs.edit().clear().commit();
    Config.initGlobalConfig(prefs, context.getResources(), false, null);
    return Config.globalConfig();
  }

  private InputConnection input_connection(final SurroundingText text)
  {
    return (InputConnection)Proxy.newProxyInstance(
        InputConnection.class.getClassLoader(), new Class[] { InputConnection.class },
        new InvocationHandler()
        {
          public Object invoke(Object proxy, Method method, Object[] args)
          {
            if (method.getName().equals("getSurroundingText"))
              return text;
            Class<?> type = method.getReturnType();
            if (type == Boolean.TYPE) return false;
            if (type == Integer.TYPE) return 0;
            return null;
          }
        });
  }
}
