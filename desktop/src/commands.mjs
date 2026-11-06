export function commandSuggestions(input,catalog){
  const query=input.trimStart().toLowerCase();
  if(!query.startsWith('/')||query.includes('\n'))return [];
  const subcommand=query.includes(' ');
  return catalog.filter(item=>item.command.toLowerCase().startsWith(query)&&(subcommand||!item.command.includes(' '))).slice(0,40);
}
export function needsModel(input,catalog){
  const text=input.trim();
  if(!text.startsWith('/'))return true;
  const normalized=text.toLowerCase().replace(/\s+/g,' ');
  const entry=catalog.filter(item=>normalized===item.command||normalized.startsWith(item.command+' ')).sort((a,b)=>b.command.length-a.command.length)[0];
  return !!entry?.requiresModel&&normalized.length>entry.command.length;
}
