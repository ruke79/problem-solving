package io.webboy.verify.labs.msa;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** "커밋 직후 프로세스가 죽었다 / 브로커가 잠깐 죽었다" 를 흉내내는 최소 브로커. */
@Component
public class FlakyBroker {

    private final List<String> delivered = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean up = true;

    public void down() {
        up = false;
    }

    public void up() {
        up = true;
    }

    public void publish(String payload) {
        if (!up) {
            throw new IllegalStateException("broker unavailable");
        }
        delivered.add(payload);
    }

    public List<String> delivered() {
        return List.copyOf(delivered);
    }

    public void reset() {
        delivered.clear();
        up = true;
    }
}
