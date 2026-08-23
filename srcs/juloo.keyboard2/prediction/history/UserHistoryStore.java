package juloo.keyboard2.prediction.history;

public interface UserHistoryStore
{
  UserHistoryModel load(String locale);
  void save(String locale, UserHistoryModel snapshot);
}
