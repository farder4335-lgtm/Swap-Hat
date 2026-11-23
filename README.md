# Auto Helmet Swap

Client-side Fabric mod for **Minecraft 26.2**. Needs Fabric Loader and Fabric API.

Three keybinds (Options > Controls > Key Binds > "Auto Helmet Swap", defaults J / K / L):

1. Superman Head ("Сила Супермена") <-> Jack Head ("Голова Джека")
2. Netherite Helmet <-> Superman Head
3. Netherite Helmet <-> Jack Head

Each press toggles between the two items. The needed item is pulled into your selected hotbar slot, put on your head,
and the helmet you were wearing goes back into the inventory slot the item came from. Your hotbar item is restored.
The player is stopped while swapping. Only vanilla inventory clicks are used, so it works on servers; no new items or recipes.

Download the JAR from the **Releases** page (built by GitHub Actions on every push).
