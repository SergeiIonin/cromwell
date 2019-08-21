version development

task WdlBrokenAssign {
  input {
    Int? runtime_cpu
    Int? ths = runtime_cpu
  }
  command {
    echo ${ths}
  }
  output {
    File out = stdout()
  }
}
