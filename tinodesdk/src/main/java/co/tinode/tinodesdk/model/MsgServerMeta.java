package co.tinode.tinodesdk.model;

import java.util.Date;

/**
 * Created by gsokolov on 2/2/16.
 */
public class MsgServerMeta<U, V> {
    public String id;
    public String topic;
    public Date ts;
    public Info info;
    public Sub[] sub;

    public MsgServerMeta() {
    }

    public class Info {
        public Date created;
        public Date updated;
        public Defacs defacs;
        public Acs acs;
        public int seq;
        public int read;
        public int recv;
        public int clear;
        public U pub;
        public V priv;
    }
    public class Sub {
        public String user;
        public Date updated;
        public String mode;
        public int read;
        public int recv;
        public int clear;
        public V priv;
        public boolean online;

        public String topic;
        public int seq;
        public String with;
        public U pub;
        public Seen seen;
    }
    public class Defacs {
        public String auth;
        public String anon;
    }

    public class Acs {
        public String want;
        public String given;
    }

    public class Seen {
        public Date when;
        public String ua;
    }
}
