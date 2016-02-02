package co.tinode.tinodesdk.model;

/**
 * Created by gene on 31/01/16.
 */
public class MsgClientSub {
    public String id;
    public String topic;
    public String get;
    public Init init;
    public Sub sub;
    public Browse browse;


    public MsgClientSub() {
    }

    public class Init<T,U> {
        public Defacs defacs;
        public T publ;
        public U priv;
    }
    public class Sub<V> {
        public String mode;
        public V info;
    }
    public class Browse {
        public Integer since;
        public Integer before;
        public Integer limit;
    }
    public class Defacs {
        public String auth;
        public String anon;
    }
}
