import React from 'react';
import './Tabs.css';

interface TabsProps {
    activeTab: string;
    onTabChange: (tab: string) => void;
    tabs: { label: string; value: string }[];
    align?: 'left' | 'center' | 'right';
}

const Tabs: React.FC<TabsProps> = ({ activeTab, onTabChange, tabs, align = 'center' }) => (
    <div className={`tabs tabs-align-${align}`}>
        {tabs.map(tab => (
            <button
                key={tab.value}
                className={`tab-btn${activeTab === tab.value ? ' active' : ''}`}
                onClick={() => onTabChange(tab.value)}
                type="button"
            >
                {tab.label}
            </button>
        ))}
    </div>
);

export default Tabs;
