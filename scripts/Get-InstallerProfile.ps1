param([ValidateSet('Private','AlphaCandidate')][string]$Profile = 'Private')
# These identities are product constants, not generated for each build. Alpha
# updates use the same public UpgradeCode; private test installs remain separate.
if ($Profile -eq 'Private') {
    [pscustomobject]@{
        directory='StreamBridge Private Alpha'; menu='StreamBridge Private Alpha'; upgradeCode='e1ec4f1f-4d19-4e5a-9f5a-095ab053ba09'
        output='private-installer'; description='StreamBridge private alpha installer test'
        readme='PRIVATE-INSTALLER.txt'; purpose='PRIVATE TEST ONLY - NOT A RELEASE'
        asset='StreamBridge-0.1.0.exe'; version='0.1.0'; label='private'
    }
} else {
    [pscustomobject]@{
        directory='StreamBridge App'; menu='StreamBridge'; upgradeCode='b546a5b2-8dc5-46da-8919-8a2276425785'
        output='alpha-candidate'; description='StreamBridge 0.1.0-alpha.1'
        readme='ALPHA-INSTALLER.txt'; purpose='ALPHA CANDIDATE - PUBLICATION NOT APPROVED'
        asset='StreamBridge-0.1.0-alpha.1-windows-x64.exe'; version='0.1.0'; label='v0.1.0-alpha.1'
    }
}
