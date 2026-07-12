async function saveUser(id: string): Promise<void> {
    await fetch(`/api/users/${id}`, { method: "DELETE" });
}

async function processQueue(items: string[]): Promise<void> {
    for (const item of items) {
        await saveUser(item);
    }
}
