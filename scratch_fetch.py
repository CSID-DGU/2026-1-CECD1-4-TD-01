import paramiko

client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect('10.61.230.134', username='iot')
stdin, stdout, stderr = client.exec_command('cat /home/iot/onmom_mergeVer/counseling_gateway/jetson_gateway/app/health_proactive/sources.py')
code = stdout.read().decode()
with open("sources_downloaded.py", "w", encoding="utf-8") as f:
    f.write(code)
print("Downloaded sources.py")
