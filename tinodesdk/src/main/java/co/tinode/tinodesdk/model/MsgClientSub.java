package co.tinode.tinodesdk.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Created by gene on 31/01/16.
 *
 */

public class MsgClientSub<Pu,Pr,Inv> {
    public String id;
    public String topic;
    public String get;
    public Init<Pu,Pr> init;
    public Sub<Inv> sub;
    public Browse browse;

    public MsgClientSub() {
    }

    public class Init<Pu,Pr> {
        public Defacs defacs;
        @JsonProperty("public")
        public Pu pub;
        @JsonProperty("private")
        public Pr priv;
    }

    public class Sub<Inv> {
        public String mode;
        public Inv info;
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
