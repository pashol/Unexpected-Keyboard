package juloo.keyboard2;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConfigPredictionPreferenceTest
{
  @Test
  public void experimental_prediction_is_false_when_preference_is_absent()
  {
    assertFalse(Config.load_experimental_prediction_engine(preferences()));
  }

  @Test
  public void experimental_prediction_loads_true_preference()
  {
    Map<String, Object> values = new HashMap<>();
    values.put("experimental_prediction_engine", true);

    assertTrue(Config.load_experimental_prediction_engine(preferences(values)));
  }

  @Test
  public void ordinary_refresh_values_do_not_enable_experimental_prediction()
  {
    Map<String, Object> values = new HashMap<>();
    values.put("suggestions", true);
    values.put("space_bar_auto_complete", true);

    assertFalse(Config.load_experimental_prediction_engine(preferences(values)));
  }

  private static SharedPreferences preferences()
  {
    return preferences(new HashMap<>());
  }

  private static SharedPreferences preferences(Map<String, Object> values)
  {
    return (SharedPreferences)Proxy.newProxyInstance(
        SharedPreferences.class.getClassLoader(),
        new Class[] { SharedPreferences.class },
        (proxy, method, args) -> {
          if (method.getName().equals("getBoolean"))
            return values.containsKey(args[0]) ? values.get(args[0]) : args[1];
          if (method.getName().equals("contains"))
            return values.containsKey(args[0]);
          Class<?> type = method.getReturnType();
          if (type == Boolean.TYPE) return false;
          if (type == Integer.TYPE) return 0;
          if (type == Long.TYPE) return 0L;
          if (type == Float.TYPE) return 0f;
          return null;
        });
  }
}
