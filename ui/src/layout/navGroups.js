export const NAV_GROUPS = [
  {
    label: "Monitor",
    items: [
      { id: "dashboard", icon: "◈", label: "Dashboard"    },
      { id: "targets",   icon: "⊕", label: "Targets"      },
      { id: "certs",     icon: "⊞", label: "Certificates" },
    ],
  },
  {
    label: "Infrastructure",
    items: [
      { id: "locations", icon: "⊙", label: "Locations" },
      { id: "agents",    icon: "⬡", label: "Agents"    },
    ],
  },
  {
    label: "Account",
    items: [
      { id: "team",     icon: "◎", label: "Team"     },
      { id: "settings", icon: "⚙", label: "Settings" },
    ],
  },
];

export const MSP_GROUP = {
  label: "MSP",
  items: [
    { id: "msp-dashboard", icon: "◇", label: "MSP Dashboard" },
    { id: "msp-orgs",      icon: "⬡", label: "Client Orgs"   },
    { id: "msp-targets",   icon: "⊕", label: "All Targets"   },
  ],
};

export const ADMIN_GROUP = {
  label: "Admin",
  items: [
    { id: "platform-admin-orgs", icon: "◫", label: "All Orgs" },
  ],
};
