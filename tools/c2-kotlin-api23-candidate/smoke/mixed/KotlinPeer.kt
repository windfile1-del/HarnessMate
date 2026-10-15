class KotlinPeer { fun callJava(): String = JavaPeer().instanceValue() + ":" + JavaPeer.staticValue() }
