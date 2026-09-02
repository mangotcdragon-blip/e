type Listener = () => void;
const listeners = new Set<Listener>();

// Always starts locked. A fresh app launch, reload, or bundle refetch must never
// come up unlocked - there is deliberately no "remember unlocked" persistence.
let _locked = true;

export function isLocked() {
    return _locked;
}

export function setLocked(value: boolean) {
    if (_locked === value) return;
    _locked = value;
    listeners.forEach(l => l());
}

export function subscribeLocked(listener: Listener) {
    listeners.add(listener);
    return () => listeners.delete(listener);
}

export function useLocked() {
    const [, forceUpdate] = React.useReducer((n: number) => n + 1, 0);
    React.useEffect(() => {
        const unsubscribe = subscribeLocked(forceUpdate);
        return () => { unsubscribe(); };
    }, []);
    return _locked;
}
