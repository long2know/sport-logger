package com.long2know.sportlogger.services;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

public final class ServiceClientRegistry<T> {
    private final Set<T> clients = new CopyOnWriteArraySet<>();

    public void register(T client) {
        if (client != null) {
            clients.add(client);
        }
    }

    public void unregister(T client) {
        clients.remove(client);
    }

    public void dispatch(Consumer<T> callback) {
        for (T client : clients) {
            callback.accept(client);
        }
    }

    public boolean isEmpty() {
        return clients.isEmpty();
    }

    public int size() {
        return clients.size();
    }
}
