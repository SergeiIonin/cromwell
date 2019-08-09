version development

task echoInt {
  input {
    Int? runtime_cpu
  }
  command {
    echo ${runtime_cpu}
  }
  output {
    File out = stdout()
  }
}
