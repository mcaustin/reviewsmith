async function saveUser(id: string): Promise<void> {
    await fetch(`/api/users/${id}`, { method: "DELETE" });
}

async function processQueue(items: string[]): Promise<void> {
    for (const item of items) {
        // floating promise: saveUser returns a Promise that is not awaited,
        // so errors are silently swallowed and ordering is not preserved
        saveUser(item);
    }
}
