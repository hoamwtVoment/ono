package moe.ono.creator.forward;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Mutable in-memory draft used by PacketHelper's forward-message manager. */
public final class ForwardMessageDraft {
    private final ArrayList<ForwardMessageNode> nodes = new ArrayList<>();

    public synchronized int size() { return nodes.size(); }
    public synchronized boolean isEmpty() { return nodes.isEmpty(); }
    public synchronized ForwardMessageNode get(int index) { return nodes.get(index); }
    public synchronized void add(ForwardMessageNode node) { nodes.add(node); }
    public synchronized void set(int index, ForwardMessageNode node) { nodes.set(index, node); }
    public synchronized void remove(int index) { nodes.remove(index); }
    public synchronized void clear() { nodes.clear(); }
    public synchronized void move(int from, int to) { Collections.swap(nodes, from, to); }

    public synchronized List<ForwardMessageNode> snapshot() {
        ArrayList<ForwardMessageNode> copy = new ArrayList<>(nodes.size());
        for (ForwardMessageNode node : nodes) copy.add(node.copy());
        return copy;
    }
}
